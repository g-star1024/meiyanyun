package com.meiyun.store;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店主数据只读接口（M1）。
 * 注：门店数红线——接口层禁止返回/接受 29、108 作为门店总数（废止常量，见 BASELINE §1）。
 */
@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoreRepository storeRepository;
    private final RegionDistRepository regionDistRepository;

    public StoreController(StoreRepository storeRepository, RegionDistRepository regionDistRepository) {
        this.storeRepository = storeRepository;
        this.regionDistRepository = regionDistRepository;
    }

    @GetMapping
    @RequirePerm({"org:view", "appointment:view"})
    public List<Store> list() {
        // 数据域：STORE/SELF 只见本店，REGION 只见本区门店（JWT stores 名单），GROUP/BRAND 全量
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return storeRepository.findAll();
        }
        return storeRepository.findAll().stream()
                .filter(s -> DataScope.canReadStore(s.getStoreCode()))
                .toList();
    }

    @GetMapping("/{code}")
    @RequirePerm({"org:view", "appointment:view"})
    public Store one(@PathVariable String code) {
        Store s = storeRepository.findById(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(s.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return s;
    }

    /**
     * 批量门店名解析（服务间调用专用）：store_code → store_name。
     * 供 customer 等服务把客户归属门店编码解析为中文名，替代各服务直连 store 表的权宜做法。
     * 例：GET /api/stores/name-map?codes=SST02,SST01 → {"SST01":"上海静安店","SST02":"上海浦东店"}
     * 注：精确路径 /name-map 优先于 /{code} 匹配，不会被当成门店编码。
     */
    @GetMapping("/name-map")
    public Map<String, String> nameMap(@RequestParam(value = "codes", required = false) List<String> codes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (codes == null) return out;
        List<String> distinct = codes.stream()
                .filter(s -> s != null && !s.isBlank()).map(String::trim).distinct().toList();
        if (distinct.isEmpty()) return out;
        storeRepository.findAllById(distinct)
                .forEach(s -> out.put(s.getStoreCode(), s.getStoreName()));
        return out;
    }

    /** 六大区分布（含三层口径汇总，ID-2）。 */
    @GetMapping("/regions/dist")
    @RequirePerm("org:view")
    public List<RegionDist> regionDist() {
        return regionDistRepository.findAll();
    }
}
