// Package proxy 实现国密网关的反向代理路由：将 B 端/C 端流量按路径前缀转发到后端微服务。
package proxy

import (
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
)

// 路由表（与后端服务端口一致）。
// 每条前缀自动注册「精确 + 尾斜杠」两个模式：
// Go http.ServeMux 只注册 "/api/x/"（子树）时，精确路径 "/api/x" 会 301 重定向，
// 导致不跟随重定向的客户端拿到空响应（裁定 #5：统一双写根治）。
//
// 上游地址支持环境变量覆盖（如 CUSTOMER_SERVICE_URL），默认回退 127.0.0.1。
// 这样同一份二进制既可在宿主机直连（127.0.0.1），也可在 Docker 容器内通过
// 服务名寻址（如 http://customer-service:8082），无需为不同部署改代码。
type routeEntry struct {
	base      string // 不带尾斜杠的前缀，如 /api/customer
	envKey    string // 覆盖用的环境变量名
	fallback  string // 默认上游地址
}

var routeTable = []routeEntry{
	{"/api/stores", "STORES_SERVICE_URL", "http://127.0.0.1:8085"},       // store-service
	{"/api/customer", "CUSTOMER_SERVICE_URL", "http://127.0.0.1:8082"},  // customer-service（含 mall）
	{"/api/txn", "TXN_SERVICE_URL", "http://127.0.0.1:8083"},            // txn-service
	{"/api/audit", "AUDIT_SERVICE_URL", "http://127.0.0.1:8084"},        // audit-service
	{"/api/org", "ORG_SERVICE_URL", "http://127.0.0.1:8086"},            // org-service
	{"/api/finance", "FINANCE_SERVICE_URL", "http://127.0.0.1:8087"},    // finance-service
	{"/api/marketing", "MARKETING_SERVICE_URL", "http://127.0.0.1:8088"}, // marketing-service
}

// resolveTarget 读取环境变量，缺失时回退默认值。
func resolveTarget(e routeEntry) string {
	if v := os.Getenv(e.envKey); v != "" {
		return v
	}
	return e.fallback
}

// NewHandler 构建反向代理处理器。
func NewHandler() http.Handler {
	mux := http.NewServeMux()
	for _, r := range routeTable {
		target, err := url.Parse(resolveTarget(r))
		if err != nil {
			panic(err)
		}
		// 保留原始路径前缀转发到后端
		p := httputil.NewSingleHostReverseProxy(target)
		// 双写：精确前缀 + 尾斜杠子树，避免 301
		mux.Handle(r.base, p)
		mux.Handle(r.base+"/", p)
	}
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	return mux
}
