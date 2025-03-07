package service

import (
	"context"
	"fmt"
	"time"

	"github.com/ai-generation/pkg/redis"
)

// CacheService Redis缓存服务
type CacheService struct {
	redisClient *redis.Client
}

// NewCacheService 创建缓存服务实例
func NewCacheService(redisClient *redis.Client) *CacheService {
	return &CacheService{redisClient: redisClient}
}

// CacheKey 生成缓存键
func (s *CacheService) CacheKey(query string, user string) string {
	return fmt.Sprintf("completion:%s:%s", user, query)
}

// RateLimitKey 生成限流键
func (s *CacheService) RateLimitKey(user string) string {
	return fmt.Sprintf("rate_limit:%s", user)
}

// GetCachedCompletion 获取缓存的完成结果
func (s *CacheService) GetCachedCompletion(ctx context.Context, query string, user string) (map[string]interface{}, error) {
	var result map[string]interface{}
	err := s.redisClient.Get(ctx, s.CacheKey(query, user), &result)
	return result, err
}

// SetCachedCompletion 设置完成结果缓存
func (s *CacheService) SetCachedCompletion(ctx context.Context, query string, user string, result map[string]interface{}) error {
	return s.redisClient.Set(ctx, s.CacheKey(query, user), result, 24*time.Hour)
}

// CheckRateLimit 检查用户请求限流
func (s *CacheService) CheckRateLimit(ctx context.Context, user string, limit int, window time.Duration) (bool, error) {
	key := s.RateLimitKey(user)
	exists, err := s.redisClient.Exists(ctx, key)
	if err != nil {
		return false, err
	}

	if !exists {
		// 首次请求，设置计数器为1
		err = s.redisClient.Set(ctx, key, 1, window)
		return true, err
	}

	// 获取当前计数
	var count int
	err = s.redisClient.Get(ctx, key, &count)
	if err != nil {
		return false, err
	}

	if count >= limit {
		return false, nil
	}

	// 增加计数
	count++
	err = s.redisClient.Set(ctx, key, count, window)
	return true, err
}
