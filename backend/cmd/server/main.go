package main

import (
	"context"
	"fmt"
	"github.com/ai-generation/pkg/redis"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/ai-generation/api/proto"
	"github.com/ai-generation/internal/api"
	"github.com/ai-generation/internal/config"
	"github.com/ai-generation/internal/grpc"
	"github.com/ai-generation/internal/service"
	"github.com/gin-gonic/gin"
	grpclib "google.golang.org/grpc"
)

func main() {
	// 加载配置
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	// 初始化Redis客户端
	redisClient := redis.NewClient(&redis.Config{
		Addr:     cfg.RedisAddr,
		Password: cfg.RedisPassword,
		DB:       cfg.RedisDB,
	})

	// 初始化缓存服务
	cacheService := service.NewCacheService(redisClient)

	// 初始化AI服务
	aiService, error := service.NewAIService(cfg.DifyAPIKey, cfg.DifyAPIEndpoint, cacheService)
	// 检查ai服务是否成功创建
	if error != nil {
		log.Fatal(error)
	}
	// 创建HTTP路由
	r := gin.Default()
	api.RegisterHandlers(r, aiService)

	// 创建HTTP服务器
	httpServer := &http.Server{
		Addr:    fmt.Sprintf("%s:%s", cfg.Server.Host, cfg.Server.HTTPPort),
		Handler: r,
	}

	// 创建gRPC服务器
	grpcServer := grpclib.NewServer()
	completionServer := grpc.NewServer(aiService)
	proto.RegisterCompletionServiceServer(grpcServer, completionServer)

	// 启动HTTP服务器（后台运行）
	go func() {
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start HTTP server: %v", err)
		}
	}()

	// 启动gRPC服务器（后台运行）
	go func() {
		lis, err := net.Listen("tcp", fmt.Sprintf("%s:%s", cfg.Server.Host, cfg.Server.GRPCPort))
		if err != nil {
			log.Fatalf("Failed to listen: %v", err)
		}
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatalf("Failed to start gRPC server: %v", err)
		}
	}()

	// 等待中断信号
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	// 创建一个带超时的context
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// 优雅关闭HTTP服务器
	if err := httpServer.Shutdown(ctx); err != nil {
		log.Printf("HTTP server shutdown error: %v\n", err)
	}

	// 优雅关闭gRPC服务器
	grpcServer.GracefulStop()
	log.Println("Server shutdown gracefully")
}
