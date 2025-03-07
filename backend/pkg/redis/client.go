package redis

import (
	"context"
	"encoding/json"
	"time"

	"github.com/redis/go-redis/v9"
)

// Client Redis客户端封装
type Client struct {
	rdb *redis.Client
}

// Config Redis配置
type Config struct {
	Addr     string
	Password string
	DB       int
	PoolSize int
}

// NewClient 创建新的Redis客户端
func NewClient(cfg *Config) *Client {
	rdb := redis.NewClient(&redis.Options{
		Addr:     cfg.Addr,
		Password: cfg.Password,
		DB:       cfg.DB,
		PoolSize: cfg.PoolSize,
	})

	return &Client{rdb: rdb}
}

// Ping 检查redis是否可用
func (c *Client) Ping(ctx context.Context) error {
	return c.rdb.Ping(ctx).Err()
}

// Set 设置缓存
func (c *Client) Set(ctx context.Context, key string, value interface{}, expiration time.Duration) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return c.rdb.Set(ctx, key, data, expiration).Err()
}

// Get 获取缓存
func (c *Client) Get(ctx context.Context, key string, value interface{}) error {
	data, err := c.rdb.Get(ctx, key).Bytes()
	if err != nil {
		return err
	}
	return json.Unmarshal(data, value)
}

// Del 删除缓存
func (c *Client) Del(ctx context.Context, key string) error {
	return c.rdb.Del(ctx, key).Err()
}

// Exists 检查key是否存在
func (c *Client) Exists(ctx context.Context, key string) (bool, error) {
	result, err := c.rdb.Exists(ctx, key).Result()
	return result > 0, err
}

// Close 关闭连接
func (c *Client) Close() error {
	return c.rdb.Close()
}
