package types

// CompletionRequest 定义了完成请求的数据结构
type CompletionRequest struct {
	Query        string            `json:"query" binding:"required"`
	Inputs       map[string]string `json:"inputs"`
	User         string            `json:"user,omitempty"`
	ResponseMode string            `json:"response_mode,omitempty"`
}

//// CompletionData 定义了完成响应中的数据结构
//type CompletionData struct {
//	Event          string                 `json:"event"`
//	TaskID         string                 `json:"task_id"`
//	ID             string                 `json:"id"`
//	MessageID      string                 `json:"message_id"`
//	ConversationId string                 `json:"conversation_id"`
//	Mode           string                 `json:"mode"`
//	Answer         map[string]interface{} `json:"answer"`
//	Text           string                 `json:"text"`
//	Outputs        map[string]string      `json:"outputs"`
//	CreatedAt      string                 `json:"created_at"`
//	Metadata       map[string]interface{} `json:"metadata,omitempty"`
//}
/**
**响应格式**：
  ```json
  {
    "code": 200,
    "msg": "success",
    "data": { "content": "这里是机器人的回答文本",
              "functions": [
                         { "action": "handsup", "delay": 0 },
                         { "action": "voice", "params": "语音内容", "delay": 500 },
                         { "action": "handsdown", "delay": 1000 }
              ]
    }
  }
  ```
*/

type CompletionFunctions struct {
	Action string `json:"action"`
	Delay  int    `json:"delay"`
	Params string `json:"params,omitempty"`
}

type CompletionOptimizeData struct {
	Content   string                `json:"content"`
	Functions []CompletionFunctions `json:"functions"`
}

type CompletionResponse struct {
	Code int                     `json:"code"`
	Msg  string                  `json:"msg"`
	Data *CompletionOptimizeData `json:"data"`
}
