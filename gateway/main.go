// 美研云国密网关（Go）。
// 监听国密 TLS（GM/T 0024，SM2+SM4+SM3）+ 国际 TLS 双栈，反向代理后端微服务。
// 决策依据：P1-D02 —— Phase 1 实做真国密，自签 SM2 根证书，无既有 CA 可对接。
package main

import (
	"crypto/tls"
	"log"
	"net/http"

	"github.com/meiyun/gateway/internal/gm"
	"github.com/meiyun/gateway/internal/proxy"
	"github.com/tjfoc/gmsm/gmtls"
)

const listenAddr = ":8443"

func main() {
	sm2SigCert, err := gm.GenerateSM2SelfSigned("meiyun-gateway-sign")
	if err != nil {
		log.Fatalf("生成 SM2 签名证书失败: %v", err)
	}

	sm2EncCert, err := gm.GenerateSM2SelfSigned("meiyun-gateway-enc")
	if err != nil {
		log.Fatalf("生成 SM2 加密证书失败: %v", err)
	}

	stdCert, err := gm.GenerateStandardSelfSigned("meiyun-gateway")
	if err != nil {
		log.Fatalf("生成国际标准证书失败: %v", err)
	}

	cfg, err := gmtls.NewBasicAutoSwitchConfig(&sm2SigCert, &sm2EncCert, &stdCert)
	if err != nil {
		log.Fatalf("初始化国密双栈配置失败: %v", err)
	}
	cfg.MinVersion = tls.VersionTLS12

	ln, err := gmtls.Listen("tcp", listenAddr, cfg)
	if err != nil {
		log.Fatalf("国密 TLS 监听 %s 失败: %v", listenAddr, err)
	}

	log.Printf("美研云国密网关已启动（国密 TLS GM/T 0024 + 国际 TLS 双栈）监听 %s", listenAddr)
	log.Fatal(http.Serve(ln, proxy.NewHandler()))
}
