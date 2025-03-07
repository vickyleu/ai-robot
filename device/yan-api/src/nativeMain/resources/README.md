# YanAPI

## 简介
YanAPI 是基于 Yanshee RESTful 接口开发的、针对 Python 编程语言的 API。它提供使用 Python 获取机器人状态信息、设计控制机器人表现的能力，您可以轻松定制与众不同的专属机器人。当前版本为 2.0.5。

## 适用对象
具备一定 Python3 编程经验、了解函数接口调用方式、持有 Yanshee 机器人的用户。

## 环境依赖

### 在机器人本体系统中调用 YanAPI
- **软件依赖**：机器人固件版本 ≥ 2.1.0
- **编程开发环境**：推荐使用内置于机器人本体系统中的 JupyterLab（支持远程访问）
- **编程语言**：Python 3.5.0 及以上版本

### 在 PC 端调用 YanAPI
- **软件依赖**：需要将机器人本体系统 /usr/lib/python3.5 路径下的 YanAPI.py 文件拷贝到 PC 端相应 Python 开发软件依赖库的路径下
- **编程开发环境**：推荐使用
  - VSCode（https://code.visualstudio.com/）
  - PyCharm（https://www.jetbrains.com/pycharm/）
  - Jupyter（https://jupyter.org/）
- **编程语言**：Python 3.5.0 及以上版本

## 入门指南

### 1. 安装依赖
```bash
sudo apt-get -y install doxygen swig
```

### 2. 设置环境变量
```bash
export YANSHEE_SDK=/home/pi/Yanshee-SDK
export LD_LIBRARY_PATH=$YANSHEE_SDK/output/libs/:$LD_LIBRARY_PATH
```

### 3. 引入 SDK
```python
import YanAPI
```

### 4. 初始化
- **在机器人本体系统中**：无需初始化操作，无需设置机器人 IP 地址，默认操控当前机器人
- **在 PC 端**：需要设置机器人 IP 地址，且要求机器人与 PC 端接入同一局域网
```python
# 初始化 SDK
# @param robot_ip: 机器人 IP 地址
YanAPI.yan_api_init(ip_addr)
```

## 主要功能模块

### 1. 基础功能
- **电量管理**
  ```python
  get_robot_battery_info()  # 获取电量信息
  get_robot_battery_value() # 获取电量值
  ```

- **摔倒管理**
  ```python
  get_robot_fall_management_state()  # 获取摔倒管理状态
  set_robot_fall_management_state()  # 设置摔倒管理开关
  ```

- **系统设置**
  ```python
  get_robot_language()  # 获取系统语言
  set_robot_language()  # 设置系统语言
  get_robot_volume()   # 获取系统音量
  set_robot_volume()   # 设置系统音量
  ```

### 2. 语音系统 (Voice)
包含以下主要功能：
- **语音识别 (ASR)**
  ```python
  start_voice_asr()      # 开始语音识别
  get_voice_asr()        # 获取识别结果
  sync_do_voice_asr()    # 同步执行语音识别
  ```

- **语音合成 (TTS)**
  ```python
  start_voice_tts()      # 开始语音合成
  get_voice_tts_state()  # 获取合成状态
  sync_do_tts()         # 同步执行语音合成
  ```

- **语音听写 (IAT)**
  ```python
  start_voice_iat()     # 开始语音听写
  get_voice_iat()       # 获取听写结果
  sync_do_voice_iat()   # 同步执行语音听写
  ```

- **自然语言处理 (NLP)**
  ```python
  start_voice_nlp()     # 开始语义理解
  get_voice_nlp_state() # 获取语义理解状态
  sync_do_voice_nlp()   # 同步执行语义理解
  ```

### 3. 视觉系统 (Vision)
- **人脸识别**
  ```python
  start_face_recognition()       # 开始人脸识别
  sync_do_face_recognition()     # 同步执行人脸识别
  do_face_entry()               # 人脸录入
  ```

- **物体识别和追踪**
  ```python
  start_object_recognition()     # 开始物体识别
  start_object_tracking()       # 开始物体追踪
  config_object_tracking()      # 配置物体追踪参数
  ```

- **图像采集和管理**
  ```python
  take_vision_photo()           # 拍照
  get_vision_photo()           # 获取照片
  get_vision_photo_list()      # 获取照片列表
  delete_vision_photo()        # 删除照片
  ```

### 4. 运动控制系统 (Motion)
- **动作控制**
  ```python
  start_play_motion()          # 开始执行动作
  pause_play_motion()          # 暂停动作
  resume_play_motion()         # 恢复动作
  stop_play_motion()          # 停止动作
  ```

- **舵机控制**
  ```python
  get_servos_angles()         # 获取舵机角度
  set_servos_angles()         # 设置舵机角度
  set_servos_mode()          # 设置舵机模式
  ```

### 5. 传感器系统 (Sensor)
```python
get_sensors_list()           # 获取传感器列表
get_sensors_gyro()          # 获取陀螺仪数据
get_sensors_infrared()      # 获取红外传感器数据
get_sensors_ultrasonic()    # 获取超声波传感器数据
get_sensors_environment()   # 获取环境传感器数据
get_sensors_touch()         # 获取触摸传感器数据
get_sensors_pressure()      # 获取压力传感器数据
```

### 6. 订阅系统 (Subscribe)
```python
# 运动控制订阅
start_subscribe_motion()     # 订阅运动控制状态
stop_subscribe_motion()      # 停止订阅运动控制状态

# 传感器订阅
start_subscribe_sensor()     # 订阅传感器数据
stop_subscribe_sensor()      # 停止订阅传感器数据

# 视觉任务订阅
start_subscribe_vision()     # 订阅视觉任务结果
stop_subscribe_vision()      # 停止订阅视觉任务结果
```

### 7. uKit2.0 控制
```python
creat_channel_to_ukit()     # 创建通信通道
send_msg_to_ukit()         # 发送消息
get_msg_from_ukit()        # 接收消息
close_channel_to_ukit()    # 关闭通信通道
```

## 硬件特性
- 8MP 摄像头
- 2个摄像头 LED
- 2个扬声器
- HDMI 接口
- 麦克风 LED
- USB 接口
- GPIO 接口
- 6个传感器接口
- 17个舵机

## 开发注意事项
1. 使用前请确保机器人电量充足
2. 运行动作指令时请确保机器人放置平稳
3. 使用视觉功能时请确保摄像头未被遮挡
4. 首次使用需要进行网络配置
5. 建议在开发时使用稳定的电源供应
6. 所有输入的操作符及关键词均需使用半角字符，不支持全角字符

## 错误处理
标准返回格式：
```python
{
    code: integer,  # 返回码：0表示正常
    data: dict,    # 返回数据
    msg: string    # 提示信息
}
```

## 技术支持
- 文档地址：https://yandev.ubtrobot.com/
- API 文档：https://yandev.ubtrobot.com/#/zh/api?api=YanAPI
- SDK 下载：https://github.com/UBTEDU/Yanshee-SDK

## 许可证
本项目遵循 MIT 许可证 