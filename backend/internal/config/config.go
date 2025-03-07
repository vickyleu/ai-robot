package config

import (
	"os"
	"strconv"
)

type Config struct {
	Server struct {
		Host     string `json:"host"`
		HTTPPort string `json:"http_port"`
		GRPCPort string `json:"grpc_port"`
	} `json:"server"`
	DifyAPIKey      string `json:"dify_api_key"`
	DifyAPIEndpoint string `json:"dify_api_endpoint"`
	RedisAddr       string `json:"redis_addr"`
	RedisPassword   string `json:"redis_password"`
	RedisDB         int    `json:"redis_db"`
}

func Load() (*Config, error) {
	cfg := &Config{}

	// 设置默认值
	cfg.Server.Host = "0.0.0.0"
	cfg.Server.HTTPPort = "8080"
	cfg.Server.GRPCPort = "50051"
	cfg.DifyAPIEndpoint = "http://localhost"
	cfg.DifyAPIKey = "app-rWWaeSNUwtZ0Rce5iTWn1Res"
	cfg.RedisAddr = "localhost:6379"
	cfg.RedisPassword = ""
	cfg.RedisDB = 0

	// 从环境变量加载服务器配置
	if difyAPIEndpoint := os.Getenv("DIFY_API_ENDPOINT"); difyAPIEndpoint != "" {
		cfg.DifyAPIEndpoint = difyAPIEndpoint
	}
	if difyAPIKey := os.Getenv("DIFY_API_KEY"); difyAPIKey != "" {
		cfg.DifyAPIKey = difyAPIKey
	}
	if redisAddr := os.Getenv("REDIS_ADDR"); redisAddr != "" {
		cfg.RedisAddr = redisAddr
	}
	if redisPassword := os.Getenv("REDIS_PASSWORD"); redisPassword != "" {
		cfg.RedisPassword = redisPassword
	}
	if redisDB := os.Getenv("REDIS_DB"); redisDB != "" {
		if db, err := strconv.Atoi(redisDB); err == nil {
			cfg.RedisDB = db
		}
	}

	return cfg, nil
}
