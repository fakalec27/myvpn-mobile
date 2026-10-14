package option

import "github.com/sagernet/sing/common/json/badoption"

type SelectorOutboundOptions struct {
	MyVPNPreserveSelection bool `json:"myvpn_preserve_selection,omitempty"`
	Outbounds                 []string `json:"outbounds" reference:"outbound"`
	Default                   string   `json:"default,omitempty" reference:"outbound"`
	InterruptExistConnections bool     `json:"interrupt_exist_connections,omitempty"`
}

type URLTestOutboundOptions struct {
	MyVPNHealthCheck bool `json:"myvpn_health_check,omitempty"`
	MyVPNHealthURLs []string `json:"myvpn_health_urls,omitempty"`
	Outbounds                 []string           `json:"outbounds" reference:"outbound"`
	URL                       string             `json:"url,omitempty"`
	Interval                  badoption.Duration `json:"interval,omitempty"`
	Tolerance                 uint16             `json:"tolerance,omitempty"`
	IdleTimeout               badoption.Duration `json:"idle_timeout,omitempty"`
	InterruptExistConnections bool               `json:"interrupt_exist_connections,omitempty"`
}
