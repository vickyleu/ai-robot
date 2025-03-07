package api

import (
	"encoding/json"
	"net/http"

	"github.com/ai-generation/internal/types"
	"github.com/gin-gonic/gin"
)

type HTTPError struct {
	StatusCode int
	Message    string
}

func (e *HTTPError) Error() string {
	return e.Message
}

// AIService 定义了AI服务的接口
type AIService interface {
	GetCompletion(req *types.CompletionRequest) (*types.CompletionResponse, error)
}

func RegisterHandlers(r *gin.Engine, aiService AIService) {
	r.POST("/completion", func(c *gin.Context) {
		var req types.CompletionRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
			return
		}

		// 确保inputs字段不为nil
		if req.Inputs == nil {
			req.Inputs = make(map[string]string)
		}

		// 设置默认的response_mode
		if req.ResponseMode == "" {
			req.ResponseMode = "blocking"
		}

		resp, err := aiService.GetCompletion(&req)
		if err != nil {
			// 检查是否是HTTP错误
			if httpErr, ok := err.(*HTTPError); ok {
				c.JSON(httpErr.StatusCode, gin.H{"error": httpErr.Message})
				return
			}
			// 其他错误作为内部服务器错误处理
			c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
			return
		}

		// 使用json.MarshalIndent格式化响应
		formattedResp, err := json.MarshalIndent(resp, "", "  ")
		if err != nil {
			c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to format response"})
			return
		}

		c.Data(http.StatusOK, "application/json", formattedResp)
	})
}
