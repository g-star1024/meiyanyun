// Package gm 封装国密（SM2/SM3/SM4）相关能力。
// 本脚手架无既有 CA 可对接，网关启动即自签 SM2 根证书 + 终端证书，
// 用于 GM/T 0024 国密 TLS 通道（SM2 密钥协商 + SM4 会话加密 + SM3 摘要）。
package gm

import (
	"crypto/rand"
	"crypto/x509/pkix"
	"math/big"
	"time"

	"github.com/tjfoc/gmsm/gmtls"
	"github.com/tjfoc/gmsm/sm2"
	gmx509 "github.com/tjfoc/gmsm/x509"
)

// GenerateSM2SelfSigned 生成一张自签 SM2 终端证书（无既有 CA 场景）。
// 生产环境应改为：由机构国密根 CA 签发，并将根 CA 预置到客户端信任库。
func GenerateSM2SelfSigned(commonName string) (gmtls.Certificate, error) {
	priv, err := sm2.GenerateKey(rand.Reader)
	if err != nil {
		return gmtls.Certificate{}, err
	}

	// gmsm v1.4.1 的 x509.Certificate 字段与标准库兼容，Subject 使用标准库 pkix.Name。
	tmpl := &gmx509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject: pkix.Name{
			CommonName:   commonName,
			Organization: []string{"MeiYun Medical Aesthetics"},
		},
		NotBefore:             time.Now().Add(-1 * time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              gmx509.KeyUsageDigitalSignature | gmx509.KeyUsageCertSign,
		ExtKeyUsage:           []gmx509.ExtKeyUsage{gmx509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		IsCA:                  true,
		DNSNames:              []string{commonName, "localhost"},
	}

	// gmsm CreateCertificate 签名：(template, parent *Certificate, publicKey *sm2.PublicKey, signer crypto.Signer)
	der, err := gmx509.CreateCertificate(tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		return gmtls.Certificate{}, err
	}

	// gmtls.Certificate.Leaf 是 gmsm x509.Certificate 指针，用 gmsm 解析 DER。
	leaf, err := gmx509.ParseCertificate(der)
	if err != nil {
		return gmtls.Certificate{}, err
	}

	return gmtls.Certificate{
		Certificate: [][]byte{der},
		PrivateKey:  priv,
		Leaf:        leaf,
	}, nil
}
