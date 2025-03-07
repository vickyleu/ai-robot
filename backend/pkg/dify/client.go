package dify

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/time/rate"
)

type Client struct {
	apiKey   string
	endpoint string
	client   *http.Client
	timeout  time.Duration
	limiter  *rate.Limiter
}

func NewClient(apiKey, endpoint string) *Client {
	// 设置HTTP代理
	var proxyURL *url.URL

	//if os.Getenv("DISABLE_CHARLES_PROXY") != "true" {
	//	proxyURL, _ = url.Parse("http://192.168.0.7:8888")
	//}

	return &Client{
		apiKey:   apiKey,
		endpoint: endpoint,
		timeout:  10 * time.Minute,                                      // 增加超时时间到10分钟
		limiter:  rate.NewLimiter(rate.Every(time.Millisecond*100), 10), // 每秒最多10个请求
		client: &http.Client{
			Transport: &http.Transport{
				Proxy: func(_ *http.Request) (*url.URL, error) {
					return proxyURL, nil
				},
				// 设置连接池
				MaxIdleConns:        100,
				MaxIdleConnsPerHost: 100,
				MaxConnsPerHost:     100,
				IdleConnTimeout:     90 * time.Second,
				// 启用HTTP/2
				ForceAttemptHTTP2: true,
				// 启用TCP keepalive
				DialContext: (&net.Dialer{
					KeepAlive: 30 * time.Second,
					Timeout:   30 * time.Second,
				}).DialContext,
			},
		},
	}
}

type CompletionRequest struct {
	Query        string            `json:"query"`
	Inputs       map[string]string `json:"inputs"`
	User         string            `json:"user,omitempty"`
	ResponseMode string            `json:"response_mode,omitempty"`
}

type CompletionOriginResponse struct {
	Event          string                 `json:"event"`
	TaskID         string                 `json:"task_id"`
	ID             string                 `json:"id"`
	MessageID      string                 `json:"message_id"`
	ConversationId string                 `json:"conversation_id"`
	Mode           string                 `json:"mode"`
	Answer         string                 `json:"answer"`
	CreatedAt      int64                  `json:"created_at"`
	Metadata       map[string]interface{} `json:"metadata,omitempty"`
}

func (c *Client) Completion(req *CompletionRequest) (*CompletionOriginResponse, error) {
	// 应用限流
	if err := c.limiter.Wait(context.Background()); err != nil {
		return nil, fmt.Errorf("rate limit exceeded: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), c.timeout)
	defer cancel()

	// 创建一个channel用于接收结果
	respCh := make(chan *CompletionOriginResponse)
	errCh := make(chan error)
	taskIDCh := make(chan string)
	// 设置默认的response_mode为blocking
	if req.ResponseMode == "" {
		req.ResponseMode = "blocking"
	}

	// 启动goroutine处理请求
	go func() {
		defer close(respCh)
		defer close(errCh)
		defer close(taskIDCh)

		// 实现重试机制
		backoff := time.Second
		maxRetries := 3
		for i := 0; i < maxRetries; i++ {
			data, err := json.Marshal(req)
			if err != nil {
				errCh <- fmt.Errorf("marshal request: %w", err)
				return
			}

			httpReq, err := http.NewRequestWithContext(ctx, "POST", c.endpoint+"/v1/chat-messages", bytes.NewReader(data))
			if err != nil {
				errCh <- fmt.Errorf("create request: %w", err)
				return
			}

			httpReq.Header.Set("Authorization", "Bearer "+c.apiKey)
			httpReq.Header.Set("Content-Type", "application/json")

			resp, err := c.client.Do(httpReq)
			if err != nil {
				if i < maxRetries-1 && isRetryableError(err) {
					time.Sleep(backoff)
					backoff *= 2 // 指数退避
					continue
				}
				errCh <- fmt.Errorf("do request: %w", err)
				return
			}
			defer resp.Body.Close()

			if resp.StatusCode != http.StatusOK {
				if i < maxRetries-1 && isRetryableStatusCode(resp.StatusCode) {
					time.Sleep(backoff)
					backoff *= 2
					continue
				}
				errCh <- fmt.Errorf("unexpected status code: %d", resp.StatusCode)
				return
			}

			// 读取响应体
			body, err := io.ReadAll(resp.Body)
			if err != nil {
				errCh <- fmt.Errorf("read response body: %w", err)
				return
			}

			// 尝试解析JSON响应
			var result CompletionOriginResponse
			if err := json.Unmarshal(body, &result); err != nil {
				// 如果解析失败，尝试提取有效的JSON部分
				validJSON := extractValidJSON(string(body))
				if validJSON != "" {
					if err := json.Unmarshal([]byte(validJSON), &result); err != nil {
						errCh <- fmt.Errorf("parse extracted json: %w, original body: %s", err, string(body))
						return
					}
				} else {
					errCh <- fmt.Errorf("invalid json response: %s", string(body))
					return
				}
			}

			// 处理Answer字段中的JSON内容
			if result.Answer != "" {
				// 尝试修复和提取Answer中的JSON内容
				validAnswerJSON := extractValidJSON(result.Answer)
				if validAnswerJSON != "" {
					// 使用修复后的JSON内容
					result.Answer = validAnswerJSON
				}
			}

			// 发送taskID到channel
			taskIDCh <- result.TaskID
			// 发送结果到channel
			respCh <- &result
			return
		}
	}()

	// 等待结果或超时
	select {
	case result := <-respCh:
		return result, nil
	case err := <-errCh:
		return nil, err
	case taskID := <-taskIDCh:
		// 存储taskID以便后续使用
		select {
		case <-ctx.Done():
			// 发送取消请求
			if err := c.StopChatMessage(taskID, req.User); err != nil {
				// 记录取消请求失败，但不影响原始错误返回
				fmt.Printf("Failed to stop chat message: %v\n", err)
			}
			return nil, ctx.Err()
		case result := <-respCh:
			return result, nil
		case err := <-errCh:
			return nil, err
		}
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// extractValidJSON 尝试从可能包含无效字符的字符串中提取有效的JSON
func extractValidJSON(input string) string {
	// 查找第一个 '{' 和最后一个 '}'
	start := strings.Index(input, "{")
	end := strings.LastIndex(input, "}")

	if start == -1 || end == -1 || end <= start {
		return ""
	}

	// 提取可能的JSON部分
	potentialJSON := input[start : end+1]

	// 验证是否为有效的JSON
	var js json.RawMessage
	if err := json.Unmarshal([]byte(potentialJSON), &js); err != nil {
		return ""
	}

	return potentialJSON
}

// 判断是否是可重试的错误
func isRetryableError(err error) bool {
	// 网络超时、连接重置等错误可以重试
	if netErr, ok := err.(net.Error); ok {
		return netErr.Temporary() || netErr.Timeout()
	}
	return false
}

// 判断是否是可重试的状态码
func isRetryableStatusCode(statusCode int) bool {
	return statusCode == http.StatusTooManyRequests || // 429
		statusCode == http.StatusServiceUnavailable || // 503
		statusCode == http.StatusGatewayTimeout // 504
}

func (c *Client) DeleteConversation(conversationId string, user string) error {
	ctx, cancel := context.WithTimeout(context.Background(), c.timeout)
	defer cancel()

	data, err := json.Marshal(map[string]string{"user": user})
	if err != nil {
		return fmt.Errorf("marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, "DELETE", fmt.Sprintf("%s/v1/conversations/%s", c.endpoint, conversationId), bytes.NewReader(data))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}

	httpReq.Header.Set("Authorization", "Bearer "+c.apiKey)
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.client.Do(httpReq)
	if err != nil {
		return fmt.Errorf("do request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status code: %d", resp.StatusCode)
	}

	return nil
}

func (c *Client) StopChatMessage(taskId string, user string) error {
	ctx, cancel := context.WithTimeout(context.Background(), c.timeout)
	defer cancel()

	data, err := json.Marshal(map[string]string{"user": user})
	if err != nil {
		return fmt.Errorf("marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, "POST", fmt.Sprintf("%s/v1/chat-messages/%s/stop", c.endpoint, taskId), bytes.NewReader(data))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}

	httpReq.Header.Set("Authorization", "Bearer "+c.apiKey)
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.client.Do(httpReq)
	if err != nil {
		return fmt.Errorf("do request: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, err := io.ReadAll(resp.Body)
		if err != nil {
			return fmt.Errorf("read error response: %w (status code: %d)", err, resp.StatusCode)
		}
		return fmt.Errorf("unexpected status code: %d, body: %s", resp.StatusCode, string(respBody))
	}

	var result map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("decode response: %w", err)
	}

	return nil
}
