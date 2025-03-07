package service

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"

	"github.com/ai-generation/internal/api"
	"github.com/ai-generation/internal/types"
	"github.com/ai-generation/pkg/dify"
)

type AIService struct {
	difyClient   *dify.Client
	cacheService *CacheService
}

func NewAIService(difyAPIKey, difyAPIEndpoint string, cacheService *CacheService) (*AIService, error) {
	// 检查Redis连接是否可用
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := cacheService.redisClient.Ping(ctx); err != nil {
		return nil, fmt.Errorf("redis连接检查失败: %v", err)
	}
	// 打印redis连接成功
	fmt.Println("redis连接可用")

	return &AIService{
		difyClient:   dify.NewClient(difyAPIKey, difyAPIEndpoint),
		cacheService: cacheService,
	}, nil
}

// 对象池定义
var completionResponsePool = sync.Pool{
	New: func() interface{} {
		return &types.CompletionResponse{
			Data: &types.CompletionOptimizeData{
				Functions: make([]types.CompletionFunctions, 0),
			},
		}
	},
}

var difyRequestPool = sync.Pool{
	New: func() interface{} {
		return &dify.CompletionRequest{
			Inputs: make(map[string]string),
		}
	},
}

var jsonDataPool = sync.Pool{
	New: func() interface{} {
		return make(map[string]interface{})
	},
}

func (s *AIService) GetCompletion(req *types.CompletionRequest) (*types.CompletionResponse, error) {
	// 从对象池获取响应对象
	resp := completionResponsePool.Get().(*types.CompletionResponse)
	// 确保函数结束时将对象放回池中
	defer func() {
		// 清理对象数据
		resp.Code = 0
		resp.Msg = ""
		if resp.Data != nil {
			resp.Data.Content = ""
			resp.Data.Functions = resp.Data.Functions[:0] // 清空切片但保留容量
		}
		completionResponsePool.Put(resp)
	}()

	// 检查限流
	allowed, err := s.cacheService.CheckRateLimit(context.Background(), req.User, 100, time.Minute)
	if err != nil {
		resp.Code = http.StatusInternalServerError
		resp.Msg = fmt.Sprintf("rate limit check failed: %v", err)
		resp.Data = nil // 显式设置为nil保证JSON序列化为null
		return resp, nil
	}
	if !allowed {
		resp.Code = http.StatusTooManyRequests
		resp.Msg = "rate limit exceeded"
		resp.Data = nil // 显式设置为nil保证JSON序列化为null
		return resp, nil
	}
	// 检查缓存
	cachedResult, err := s.cacheService.GetCachedCompletion(context.Background(), req.Query, req.User)
	if err == nil && cachedResult != nil {
		// 从缓存结果构建响应
		if content, ok := cachedResult["content"].(string); ok {
			resp.Code = http.StatusOK
			resp.Msg = "success (cached)"
			resp.Data = &types.CompletionOptimizeData{
				Content:   content,
				Functions: make([]types.CompletionFunctions, 0),
			}
			// 打印构建的响应数据
			if functions, ok := cachedResult["functions"].([]interface{}); ok {
				for _, f := range functions {
					if funcMap, ok := f.(map[string]interface{}); ok {
						function := types.CompletionFunctions{
							Action: funcMap["action"].(string),
							Delay:  int(funcMap["delay"].(float64)),
						}
						if params, ok := funcMap["params"].(string); ok {
							function.Params = params
						}
						resp.Data.Functions = append(resp.Data.Functions, function)
					}
				}
			}

			// 创建响应对象的深拷贝
			responseCopy := *resp
			responseCopy.Data = &types.CompletionOptimizeData{
				Content:   resp.Data.Content,
				Functions: append([]types.CompletionFunctions{}, resp.Data.Functions...),
			}

			// 返回深拷贝的响应对象
			return &responseCopy, nil
		}
	}

	// 确保inputs字段不为nil
	if req.Inputs == nil {
		req.Inputs = make(map[string]string)
	}

	// 确保User字段不为空
	if req.User == "" {
		req.User = "default_user"
	}

	// 从对象池获取Dify请求对象
	difyReq := difyRequestPool.Get().(*dify.CompletionRequest)
	defer func() {
		// 清理请求对象数据
		difyReq.Query = ""
		for k := range difyReq.Inputs {
			delete(difyReq.Inputs, k)
		}
		difyReq.User = ""
		difyReq.ResponseMode = ""
		difyRequestPool.Put(difyReq)
	}()

	// 填充请求数据
	difyReq.Query = req.Query
	for k, v := range req.Inputs {
		difyReq.Inputs[k] = v
	}
	difyReq.User = req.User
	difyReq.ResponseMode = req.ResponseMode

	difyResp, err := s.difyClient.Completion(difyReq)

	if err != nil {
		// 检查是否是HTTP错误并直接传递
		if httpErr, ok := err.(*api.HTTPError); ok {
			resp.Code = httpErr.StatusCode
			resp.Msg = httpErr.Message
			resp.Data = nil // 显式设置为nil保证JSON序列化为null
			return resp, nil
		}
		resp.Code = http.StatusInternalServerError
		resp.Msg = fmt.Sprintf("dify completion failed: %v", err)

		// 创建响应对象的深拷贝
		responseCopy := *resp
		// 如果原始Data为nil，则在深拷贝中也设置为nil
		if resp.Data == nil {
			responseCopy.Data = nil
		} else {
			responseCopy.Data = &types.CompletionOptimizeData{
				Content:   resp.Data.Content,
				Functions: append([]types.CompletionFunctions{}, resp.Data.Functions...),
			}
		}

		// 在深拷贝完成后再设置原始resp.Data为nil
		resp.Data = nil // 显式设置为nil保证JSON序列化为null

		// 返回深拷贝的响应对象
		return &responseCopy, nil
	}

	// 验证响应数据的有效性
	if difyResp == nil {
		resp.Code = http.StatusInternalServerError
		resp.Msg = "invalid response: empty response from dify"
		resp.Data = nil // 显式设置为nil保证JSON序列化为null
		// 创建响应对象的深拷贝
		responseCopy := *resp
		responseCopy.Data = &types.CompletionOptimizeData{
			Content:   resp.Data.Content,
			Functions: append([]types.CompletionFunctions{}, resp.Data.Functions...),
		}

		// 返回深拷贝的响应对象
		return &responseCopy, nil
	}
	// 验证必要字段
	if difyResp.Answer == "" {
		resp.Code = http.StatusInternalServerError
		resp.Msg = "invalid response: empty answer from dify"
		resp.Data = nil // 显式设置为nil保证JSON序列化为null
		// 创建响应对象的深拷贝
		responseCopy := *resp
		responseCopy.Data = &types.CompletionOptimizeData{
			Content:   resp.Data.Content,
			Functions: append([]types.CompletionFunctions{}, resp.Data.Functions...),
		}

		// 返回深拷贝的响应对象
		return &responseCopy, nil
	}

	// 从对象池获取JSON数据对象
	answerData := jsonDataPool.Get().(map[string]interface{})
	defer func() {
		// 清理JSON数据
		for k := range answerData {
			delete(answerData, k)
		}
		jsonDataPool.Put(answerData)
	}()

	// 尝试解析Answer字段为JSON对象
	if err := json.Unmarshal([]byte(difyResp.Answer), &answerData); err != nil {
		// 如果解析失败，返回错误
		fmt.Printf("Answer字段解析失败: %v\n", err)
		resp.Code = http.StatusInternalServerError
		resp.Msg = fmt.Sprintf("invalid JSON format in answer: %v", err)
		resp.Data = nil // 显式设置为nil保证JSON序列化为null
		// 创建响应对象的深拷贝
		responseCopy := *resp
		responseCopy.Data = &types.CompletionOptimizeData{
			Content:   resp.Data.Content,
			Functions: append([]types.CompletionFunctions{}, resp.Data.Functions...),
		}

		// 返回深拷贝的响应对象
		return &responseCopy, nil
	}

	// 构建响应数据
	resp.Code = http.StatusOK
	resp.Msg = "success"
	resp.Data = &types.CompletionOptimizeData{
		Content:   "",
		Functions: make([]types.CompletionFunctions, 0),
	}

	// 提取content
	if answerData != nil {
		fmt.Printf("解析到的完整answerData: %+v\n", answerData)
		if content, ok := answerData["content"].(string); ok {
			fmt.Printf("成功提取content，值为: %s\n", content)
			resp.Data.Content = content
		} else {
			fmt.Printf("content字段类型断言失败，实际类型: %T，实际值: %v\n", answerData["content"], answerData["content"])
		}

		// 提取functions
		if functions, ok := answerData["functions"].([]interface{}); ok {
			fmt.Printf("成功获取functions数组，长度: %d\n", len(functions))
			for i, f := range functions {
				fmt.Printf("处理第%d个function\n", i+1)
				if funcMap, ok := f.(map[string]interface{}); ok {
					fmt.Printf("function数据: %+v\n", funcMap)
					function := types.CompletionFunctions{
						Action: "",
						Delay:  0,
					}

					if action, ok := funcMap["action"].(string); ok {
						fmt.Printf("成功提取action: %s\n", action)
						function.Action = action
					} else {
						fmt.Printf("action字段类型断言失败，实际类型: %T，实际值: %v\n", funcMap["action"], funcMap["action"])
					}

					if delay, ok := funcMap["delay"].(float64); ok {
						fmt.Printf("成功提取delay: %f\n", delay)
						function.Delay = int(delay)
					} else {
						fmt.Printf("delay字段类型断言失败，实际类型: %T，实际值: %v\n", funcMap["delay"], funcMap["delay"])
					}

					if params, ok := funcMap["params"].(string); ok {
						fmt.Printf("成功提取params: %s\n", params)
						function.Params = params
					} else if params, ok := funcMap["params"].(map[string]interface{}); ok {
						// 如果params是一个对象，将其转换为JSON字符串
						if paramsJSON, err := json.Marshal(params); err == nil {
							fmt.Printf("成功将params对象转换为JSON字符串: %s\n", string(paramsJSON))
							function.Params = string(paramsJSON)
						} else {
							fmt.Printf("params对象转JSON失败: %v\n", err)
						}
					} else {
						fmt.Printf("params字段类型断言失败，实际类型: %T，实际值: %v\n", funcMap["params"], funcMap["params"])
					}

					resp.Data.Functions = append(resp.Data.Functions, function)
				} else {
					fmt.Printf("function不是一个有效的map，实际类型: %T\n", f)
				}
			}
		} else {
			fmt.Printf("functions字段类型断言失败或不存在，实际类型: %T，实际值: %v\n", answerData["functions"], answerData["functions"])
		}

		// 缓存结果
		if err := s.cacheService.SetCachedCompletion(context.Background(), req.Query, req.User, answerData); err != nil {
			// 缓存失败仅记录日志，不影响正常响应
			fmt.Printf("failed to cache completion result: %v\n", err)
		}
	}

	// 创建响应对象的深拷贝
	responseCopy := *resp
	responseCopy.Data = &types.CompletionOptimizeData{
		Content:   resp.Data.Content,
		Functions: append([]types.CompletionFunctions{}, resp.Data.Functions...),
	}

	// 返回深拷贝的响应对象
	return &responseCopy, nil
}
