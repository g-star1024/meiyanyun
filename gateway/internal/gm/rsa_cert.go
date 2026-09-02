package gm

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"time"

	"github.com/tjfoc/gmsm/gmtls"
)

// GenerateStandardSelfSigned 生成一张自签 ECDSA 国际标准证书，
// 返回 gmtls.Certificate 类型，用于 gmtls.NewBasicAutoSwitchConfig 的 stdCert 参数。
func GenerateStandardSelfSigned(commonName string) (gmtls.Certificate, error) {
	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return gmtls.Certificate{}, err
	}

	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject: pkix.Name{
			CommonName:   commonName,
			Organization: []string{"MeiYun Medical Aesthetics"},
		},
		NotBefore:             time.Now().Add(-1 * time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{commonName, "localhost"},
	}

	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &priv.PublicKey, priv)
	if err != nil {
		return gmtls.Certificate{}, err
	}

	return gmtls.Certificate{
		Certificate: [][]byte{der},
		PrivateKey:  priv,
	}, nil
}
