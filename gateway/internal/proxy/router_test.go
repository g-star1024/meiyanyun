package proxy

import (
	"net/http"
	"testing"
)

func TestRemoteHost(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		{"ipv4带端口", "10.12.21.45:54321", "10.12.21.45"},
		{"ipv6带端口", "[2001:db8::1]:54321", "2001:db8::1"},
		{"无端口原样返回", "10.12.21.45", "10.12.21.45"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := remoteHost(c.in); got != c.want {
				t.Fatalf("remoteHost(%q) = %q, want %q", c.in, got, c.want)
			}
		})
	}
}

func TestClientIPFromRequest(t *testing.T) {
	cases := []struct {
		name       string
		xff        string
		xrealIP    string
		remoteAddr string
		want       string
	}{
		{
			name:       "XFF首段优先于XRealIP和对端",
			xff:        "203.0.113.7, 10.0.0.1",
			xrealIP:    "10.0.0.1",
			remoteAddr: "10.0.0.1:41000",
			want:       "203.0.113.7",
		},
		{
			name:       "XFF首段去空白",
			xff:        "  203.0.113.8 , 10.0.0.1",
			remoteAddr: "10.0.0.1:41000",
			want:       "203.0.113.8",
		},
		{
			name:       "无XFF回落XRealIP",
			xrealIP:    "198.51.100.9",
			remoteAddr: "172.16.0.3:41000",
			want:       "198.51.100.9",
		},
		{
			name:       "直连网关回落RemoteAddr剥端口",
			remoteAddr: "192.168.3.21:51234",
			want:       "192.168.3.21",
		},
		{
			name:       "XFF为空白条目时继续回落",
			xff:        "  ",
			xrealIP:    "198.51.100.10",
			remoteAddr: "172.16.0.3:41000",
			want:       "198.51.100.10",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			r := &http.Request{
				Header:     http.Header{},
				RemoteAddr: c.remoteAddr,
			}
			if c.xff != "" {
				r.Header.Set("X-Forwarded-For", c.xff)
			}
			if c.xrealIP != "" {
				r.Header.Set("X-Real-IP", c.xrealIP)
			}
			if got := clientIPFromRequest(r); got != c.want {
				t.Fatalf("clientIPFromRequest() = %q, want %q", got, c.want)
			}
		})
	}
}

func TestInternalGuardStillBlocksInternalPaths(t *testing.T) {
	blocked := []string{
		"/api/audit/internal/chain/verify",
		"/api/txn/internal/foo",
	}
	for _, p := range blocked {
		if !isInternalPath(p) {
			t.Fatalf("isInternalPath(%q) = false, want true", p)
		}
	}
	allowed := []string{
		"/api/audit/logs",
		"/api/org/auth/login",
		"/api/customer",
	}
	for _, p := range allowed {
		if isInternalPath(p) {
			t.Fatalf("isInternalPath(%q) = true, want false", p)
		}
	}
}
