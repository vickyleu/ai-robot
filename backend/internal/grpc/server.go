package grpc

import (
	"context"
	"fmt"
	"os"

	"github.com/ai-generation/api/proto"
	"github.com/ai-generation/internal/types"
	"google.golang.org/protobuf/types/known/structpb"
	"google.golang.org/protobuf/types/known/wrapperspb"
)

// Server 实现gRPC服务端
type Server struct {
	proto.UnimplementedCompletionServiceServer
	aiService AIService
}

// AIService 定义了AI服务的接口
type AIService interface {
	GetCompletion(req *types.CompletionRequest) (*types.CompletionResponse, error)
}

// NewServer 创建新的gRPC服务器实例
func NewServer(aiService AIService) *Server {
	return &Server{aiService: aiService}
}

// GetCompletion 实现gRPC服务接口
func (s *Server) GetCompletion(ctx context.Context, req *proto.CompletionRequest) (*proto.CompletionResponse, error) {
	// 处理请求取消
	if ctx.Err() == context.Canceled {
		return &proto.CompletionResponse{
			Code: wrapperspb.Int32(499),
			Msg:  wrapperspb.String("request cancelled"),
			Data: structpb.NewNullValue(),
		}, nil
	}

	// 转换请求格式
	internalReq := &types.CompletionRequest{
		Query:        req.Query,
		Inputs:       req.Inputs,
		User:         req.User,
		ResponseMode: req.GetResponseMode(),
	}
	// 调用内部服务
	resp, err := s.aiService.GetCompletion(internalReq)

	if err != nil {
		fmt.Fprintf(os.Stderr, "internal service error: %v\n", err)
		return &proto.CompletionResponse{
			Code: wrapperspb.Int32(500),
			Msg:  wrapperspb.String(fmt.Sprintf("internal service error: %v", err)),
			Data: structpb.NewNullValue(),
		}, nil
	}

	// 检查响应是否为空
	if resp == nil {
		fmt.Fprintf(os.Stderr, "empty response from internal service\n")
		return &proto.CompletionResponse{
			Code: wrapperspb.Int32(500),
			Msg:  wrapperspb.String("empty response from internal service"),
			Data: structpb.NewNullValue(),
		}, nil
	}
	if resp.Code == 0 {
		resp.Code = 500
	}
	if resp.Code != 200 && resp.Msg == "" {
		resp.Msg = "internal service error"
	}
	// 检查Data字段
	if resp.Data == nil {
		fmt.Fprintf(os.Stderr, "response data is nil, code: %d, msg: %s\n", resp.Code, resp.Msg)
		return &proto.CompletionResponse{
			Code: wrapperspb.Int32(int32(resp.Code)),
			Msg:  wrapperspb.String(resp.Msg),
			Data: structpb.NewNullValue(),
		}, nil
	}
	// 确保Data字段已初始化
	if resp.Data.Content == "" || resp.Data.Functions == nil {
		fmt.Fprintf(os.Stderr, "response data fields are not initialized, code: %d, msg: %s\n", resp.Code, resp.Msg)
		return &proto.CompletionResponse{
			Code: wrapperspb.Int32(int32(resp.Code)),
			Msg:  wrapperspb.String(resp.Msg),
			Data: structpb.NewNullValue(),
		}, nil
	}

	// 将 CompletionOptimizeData 转换为 map
	dataMap := make(map[string]interface{})
	dataMap["content"] = resp.Data.Content

	// 转换functions数组
	functions := make([]interface{}, len(resp.Data.Functions))
	for i, f := range resp.Data.Functions {
		functionMap := make(map[string]interface{})
		functionMap["action"] = f.Action
		functionMap["delay"] = float64(f.Delay)
		if f.Params != "" {
			functionMap["params"] = f.Params
		}
		functions[i] = functionMap
	}
	dataMap["functions"] = functions

	// 将map转换为proto.Struct
	answerStruct, err := structpb.NewStruct(dataMap)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to convert answer to protobuf struct: %v\n", err)
		return &proto.CompletionResponse{
			Code: wrapperspb.Int32(500),
			Msg:  wrapperspb.String(fmt.Sprintf("failed to convert answer to protobuf struct: %v", err)),
			Data: structpb.NewNullValue(),
		}, nil
	}

	return &proto.CompletionResponse{
		Code: wrapperspb.Int32(int32(resp.Code)),
		Msg:  wrapperspb.String(resp.Msg),
		Data: structpb.NewStructValue(answerStruct),
	}, nil
}
