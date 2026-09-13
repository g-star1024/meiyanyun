package com.meiyun.ai.profile;

import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户画像引擎（A1-02）业务出口。
 * 面向网关业务角色，类级仅需 aiProfile:view；能否真正出站由 profile 功能的
 * ai_feature_role 灰度矩阵在 FeatureInvokeService 内判定，生成/应用动作经审计链留痕。
 */
@RestController
@RequestMapping("/api/ai/profile")
@RequirePerm("aiProfile:view")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/search")
    public List<ProfileService.CandidateView> search(@RequestParam String keyword) {
        return profileService.search(keyword);
    }

    @PostMapping("/generate")
    public ProfileService.ProfileView generate(@RequestBody ProfileService.ProfileCmd cmd) {
        return profileService.generate(cmd);
    }

    @GetMapping("/latest")
    public ProfileService.ProfileView latest(@RequestParam String customerId) {
        return profileService.latest(customerId);
    }

    @GetMapping("/records")
    public Page<ProfileService.ProfileView> records(@RequestParam(required = false) String customerId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return profileService.history(customerId, page, size);
    }

    @GetMapping("/stats")
    public ProfileService.ProfileStats stats() {
        return profileService.stats();
    }

    @GetMapping("/weights")
    public ProfileService.WeightModelView weights() {
        return profileService.weights();
    }

    @GetMapping("/review")
    public ProfileService.ReviewView review() {
        return profileService.review();
    }

    @PostMapping("/{id}/apply")
    public ProfileService.ApplyResult apply(@PathVariable Long id) {
        return profileService.apply(id);
    }
}
