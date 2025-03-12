# coding=UTF-8

''' 本代码是对Yanshee中RESTful API的封装。
消息格式请参考 "http://[robotIP]:9090/v1/ui"。
这是一个开源的API，祝大家玩得开心。
'''

from io import StringIO
import sys
import requests
import json
import time
import asyncio
from typing import List
from typing import Dict
import logging
import nest_asyncio
from lib_ukit import lib_send
from socket import *
import fcntl
import struct
import subprocess
import re
import os
import cv2
from multiprocessing import Process
from enum import Enum, unique


basic_url = "http://127.0.0.1:9090/v1/"
ip = "127.0.0.1"
headers = {'Content-Type': 'application/json'}
nest_asyncio.apply()

def get_ip_address(ifname):
    s = socket(AF_INET, SOCK_DGRAM)
    return inet_ntoa(fcntl.ioctl(s.fileno(), 0x8915, struct.pack('256s', ifname[:15]))[20:24])

def yan_api_init(robot_ip: str):
    """初始化sdk

    Args:
        robot_ip(str): 机器人ip地址

    """
    global basic_url
    global ip
    basic_url = "http://"+robot_ip+":9090/v1/"
    ip = robot_ip
    logging.basicConfig(level=logging.ERROR,format="%(asctime)s %(funcName)s %(levelname)s %(message)s",datefmt = '%Y-%m-%d  %H:%M:%S %a')


def get_robot_battery_info():
    """获得机器人电量信息

    Returns:
           Dict:
           e.g::

                {
                    code:integer (int32)返回码，0表示正常
                    data:
                    {
                        voltage: integer   电池电压(单位mv)
                        charging: integer  充电状态:    1 表示正在充电      0 未充电
                        percent: integer   电量百分比
                    }
                    msg:string  提示信息
                }
    """
    devices_url = basic_url+"devices/battery"
    response = requests.get(url=devices_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_robot_battery_value():
    """获得机器人电量百分比

    Returns:
        int: 电量百分比 (0-100)

    """
    devices_url = basic_url+"devices/battery"
    response = requests.get(url=devices_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if __resIsSuccess(res):
        batteryInfo = RobotBatteryInfo(res["data"])
        return batteryInfo.batteryPercentage
    else:
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return -1


def get_robot_fall_management_state():
    """获得机器人摔倒管理状态

    Returns:
           Dict:
           e.g::

           {
                code: integer (int32)返回码，0表示正常
                data:
                    {
                        enable: boolean （True 打开 False 关闭）
                    }
                msg: string提示信息
            }

    """
    devices_url = basic_url+"devices/fall_management"
    response = requests.get(url=devices_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def set_robot_fall_management_state(enable: bool):
    """设置机器人摔倒管理开关

    Args:
        enable(bool): True 打开 False 关闭

    Returns:
           Dict:
           e.g::

                {
                    code: integer (int32) 返回码，0表示正常
                    msg: string 提示信息
                }

    """
    devices_url = basic_url+"devices/fall_management"
    param = {"enable": enable}
    json_data = json.dumps(param)
    response = requests.put(url=devices_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_robot_language():
    """获取机器人语言

    Returns:
           json: 
           e.g::

           {
                code: integer (int32)返回码，0表示正常
                data:
                                        {
                        language:string可选：zh, en Default: zh
                    }
                    msg:string提示信息
            }

    """
    languages_url = basic_url+"devices/languages"
    response = requests.get(url=languages_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def set_robot_language(language: str):
    """设置机器人语言

    Args:
        language(str): 'zh' 中文 'en' 英语

    Returns:
           json: 
           e.g::

                {
                    code: integer (int32)返回码，0表示正常
                    msg: string提示信息
                 }

    """
    languages_url = basic_url+"devices/languages"
    param = {"language": language}
    json_data = json.dumps(param)
    response = requests.put(url=languages_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def __get_robot_led_info():
    """获取机器人灯效信息
    Returns:
        RobotLedInfo: 机器人灯效信息
    """
    led_url = basic_url+"devices/led"
    response = requests.get(url=led_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if __resIsSuccess(res):
        ledInfo = RobotLedInfo(res["data"])
        return ledInfo
    else:
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return RobotLedInfo()

def get_button_led_color_value():
    """返回机器人胸口按钮灯颜色

    Returns:
        str:机器人胸口按钮灯颜色 （white,red,green,blue,yellow,purple,cyan)
    """

    return __get_robot_led_info().buttonLedColor


def get_button_led_mode_value():
    """返回机器人胸口按钮灯点亮模式

    Returns:
        str: 胸口按钮灯点亮模式(on 开,off 关,blink 闪烁,breath 呼吸)
    """

    return __get_robot_led_info().buttonLedMode


def get_eye_led_color_value():
    """返回机器人眼睛LED颜色

    Returns:
        str: 眼睛LED颜色 (red,green,blue)

    """

    return __get_robot_led_info().eyeLedColor

def get_eye_led_mode_value():
    """返回机器人眼睛LED点亮模式

    Returns:
        str: 眼睛LED点亮模式 （on 开,off 关,blink 闪烁）

    """

    return __get_robot_led_info().eyeLedMode

def get_robot_led():
    """获取机器人灯效

    Returns:
           Dict:
           e.g::
  
                {
                    code: integer返回码，0表示正常
                    data:[
                            {
                                type:string （LED灯的类型Default: button Enum: button, camera）
                                color:string （type为 button 时，取值范围如下：white/red/green/blue/yellow/purple/cyan，type camera 时，取值范围如下:red/green/blue Default: white）
                                mode: string  (当type为 button 时，取值范围如下:red/green/blue Default: white,type为 camera 时，取值范围如下:on/off/blink Default: on)
                            }
                        ]
                    msg: string提示信息
                }

    """
    led_url = basic_url+"devices/led"
    response = requests.get(url=led_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def set_robot_led(type: str, color: str, mode: str):
    """设置机器人灯效

    Args:
        type(str):LED灯的类型Default: button Enum: button, camera
        color(str):type为 button 时，取值范围如下：white/red/green/blue/yellow/purple/cyan，type camera 时，取值范围如下:red/green/blue Default: white
        mode(str):type为 button 时，取值范围如下：on/off/blink/breath ,type为 camera 时，取值范围如下:on/off/blink Default: on

    Returns:
           Dict:
           e.g::

                {
                    code: integer (int32) 返回码，0表示正常
                    msg: string提示信息
                }

    """
    led_url = basic_url+"devices/led"
    param = {"type": type, "color": color, "mode": mode}
    json_data = json.dumps(param)
    response = requests.put(url=led_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def sync_set_led(type: str, color: str, mode: str):
    """设置机器人灯效,设置完成后返回

    Args:
        type(str):LED灯的类型Default: button Enum: button, camera
        color(str):type为 button 时，取值范围如下：white/red/green/blue/yellow/purple/cyan，type camera 时，取值范围如下:red/green/blue Default: white
        mode(str):type为 button 时，取值范围如下：on/off/blink/breath ,type为 camera 时，取值范围如下:on/off/blink Default: on

    Returns:
           bool:True 设置成功   False 设置失败
    """
    res = set_robot_led(type = type, color = color, mode = mode)
    if res['code'] != 0:
        logging.error("set led failed error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return False
    coroutine = __wait_result_color(type = type, color = color, mode = mode, getFuc = get_robot_led)
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return True


def get_robot_version_info_value(type:str):
    """获取机器人版本信息
    Args:
        type（str): 指定的模块，取值范围如下：core/servo/sn

    Returns:
        str: (版本号，舵机版本号，sn号)

    """
    version_url = basic_url+"devices/versions"
    params = {'type': type}
    response = requests.get(url=version_url, headers=headers ,params=params)
    res = json.loads(str(response.content.decode("utf-8")))
    if __resIsSuccess(res):
        versionInfo = RobotVersionInfo(res["data"])
        ret = getattr(versionInfo,type)
        return ret
    else:
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return ""

def get_robot_version_info(type: str):
    """获取机器人版本信息

    Args:
        type（str): 指定的模块，取值范围如下：core/servo/sn

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    data:
                        {
                            core:string  机器人主体软件版本号(包括硬件版本)
                            servo:string 舵机版本号
                            sn：string   机器人序列号
                        }
                    msg:string提示信息
                }

    """
    version_url = basic_url+"devices/versions"
    params = {'type': type}
    response = requests.get(url=version_url, headers=headers, params=params)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_robot_mode():
    """获取机器人运行模式

    Note:
        energy_saving_mode：表示节能模式

        calibration_mode：表示校准模式

    Returns:
           json: 
           e.g::

            {
                "code": 0,
                "data": {
                            "energy_saving_mode": true(false)
                            "calibration_mode": true(false)
                        },
                "msg": "Success"
            }

    """
    request_url = basic_url+"devices/mode"
    response = requests.get(url=request_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_robot_volume_value():
    """获得机器人音量
    Returns:
        int: 机器人音量 返回-1表示获取失败
    """
    volume_url = basic_url+"devices/volume"
    response = requests.get(url=volume_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return -1
    return res["data"]["volume"] if isinstance(res["data"]["volume"], int) else -1

def get_robot_volume():
    """获得机器人音量

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                        {
                            volume:integer maximum:100
                        }
                    msg:string提示信息
                }

    """
    volume_url = basic_url+"devices/volume"
    response = requests.get(url=volume_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def set_robot_volume_value(volume: int):
    """设置机器人音量

    Args:
        volume(int):音量（0-100） 

    Returns:
        bool （True 成功 False 失败）

    """
    volume_url = basic_url+"devices/volume"
    param = {"volume": volume}
    json_data = json.dumps(param)
    response = requests.put(url=volume_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
    return (__resIsSuccess(res))

def set_robot_volume(volume: int):
    """设置机器人音量

    Args:
        volume(int):音量（0-100） 

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    msg:string提示信息
                }

    """
    volume_url = basic_url+"devices/volume"
    param = {"volume": volume}
    json_data = json.dumps(param)
    response = requests.put(url=volume_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_joystick_buttons_list():
    """获得手柄按键信息

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                        {
                            L1:integer 按键L1状态1按下0未按下
                            L2:integer 按键L2状态1按下0未按下
                            R1:integer 按键R1状态1按下0未按下
                            R2:integer 按键R2状态1按下0未按下
                            A:integer 按键A状态1按下0未按下
                            B:integer 按键B状态1按下0未按下
                            X:integer 按键X状态1按下0未按下
                            Y:integer 按键Y状态1按下0未按下
                            DPAD_UP:integer 按键DPAD_UP状态1按下0未按下
                            DPAD_DOWN:integer 按键DPAD_DOWN状态1按下0未按下
                            DPAD_LEFT:integer 按键DPAD_LEFT状态1按下0未按下
                            DPAD_RIGHT:integer 按键DPAD_RIGHT状态1按下0未按下
                            DPAD_UP_LEFT:integer 按键DPAD_UP_LEFT状态1按下0未按下
                            DPAD_UP_RIGHT:integer 按键DPAD_UP_RIGHT状态1按下0未按下
                            DPAD_DOWN_LEFT:integer 按键DPAD_DOWN_LEFT状态1按下0未按下
                            DPAD_DOWN_RIGHT:integer 按键DPAD_DOWN_RIGHT状态1按下0未按下
                            L_STICK:integer 按键L_STICK状态1按下0未按下
                            R_STICK:integer 按键R_STICK状态1按下0未按下
                            L_STICK_UP:integer 按键L_STICK_UP状态1按下0未按下
                            L_STICK_DOWN:integer 按键L_STICK_DOWN状态1按下0未按下
                            L_STICK_LEFT:integer 按键L_STICK_LEFT状态1按下0未按下
                            L_STICK_RIGHT:integer 按键L_STICK_RIGHT状态1按下0未按下
                            R_STICK_UP:integer 按键R_STICK_UP状态1按下0未按下
                            R_STICK_DOWN:integer 按键R_STICK_DOWN状态1按下0未按下
                            R_STICK_LEFT:integer 按键R_STICK_LEFT状态1按下0未按下
                            R_STICK_RIGHT:integer 按键R_STICK_RIGHT状态1按下0未按下
                            BT:integer 按键BT状态1按下0未按下
                            START:integer 按键START状态1按下0未按下
                            POWER:integer 按键POWER状态1按下0未按下
                        }
                    msg:string提示信息
                }

    """
    volume_url = basic_url+"devices/joystick"
    response = requests.get(url=volume_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_joystick_buttons_list_value():
    """获得手柄按键信息结果

    Returns:
           Dict:
           e.g::
            {
                L1:integer 按键L1状态1按下0未按下
                L2:integer 按键L2状态1按下0未按下
                R1:integer 按键R1状态1按下0未按下
                R2:integer 按键R2状态1按下0未按下
                A:integer 按键A状态1按下0未按下
                B:integer 按键B状态1按下0未按下
                X:integer 按键X状态1按下0未按下
                Y:integer 按键Y状态1按下0未按下
                DPAD_UP:integer 按键DPAD_UP状态1按下0未按下
                DPAD_DOWN:integer 按键DPAD_DOWN状态1按下0未按下
                DPAD_LEFT:integer 按键DPAD_LEFT状态1按下0未按下
                DPAD_RIGHT:integer 按键DPAD_RIGHT状态1按下0未按下
                DPAD_UP_LEFT:integer 按键DPAD_UP_LEFT状态1按下0未按下
                DPAD_UP_RIGHT:integer 按键DPAD_UP_RIGHT状态1按下0未按下
                DPAD_DOWN_LEFT:integer 按键DPAD_DOWN_LEFT状态1按下0未按下
                DPAD_DOWN_RIGHT:integer 按键DPAD_DOWN_RIGHT状态1按下0未按下
                L_STICK:integer 按键L_STICK状态1按下0未按下
                R_STICK:integer 按键R_STICK状态1按下0未按下
                L_STICK_UP:integer 按键L_STICK_UP状态1按下0未按下
                L_STICK_DOWN:integer 按键L_STICK_DOWN状态1按下0未按下
                L_STICK_LEFT:integer 按键L_STICK_LEFT状态1按下0未按下
                L_STICK_RIGHT:integer 按键L_STICK_RIGHT状态1按下0未按下
                R_STICK_UP:integer 按键R_STICK_UP状态1按下0未按下
                R_STICK_DOWN:integer 按键R_STICK_DOWN状态1按下0未按下
                R_STICK_LEFT:integer 按键R_STICK_LEFT状态1按下0未按下
                R_STICK_RIGHT:integer 按键R_STICK_RIGHT状态1按下0未按下
                BT:integer 按键BT状态1按下0未按下
                START:integer 按键START状态1按下0未按下
                POWER:integer 按键POWER状态1按下0未按下
            }

    """
    volume_url = basic_url+"devices/joystick"
    response = requests.get(url=volume_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res["data"]


######media########

def delete_media_music(name: str):
    """删除音乐文件

    只能删除用户上传的文件 /home/pi/Document/music

    Args:
        name(str):要删除的音乐名

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    msg:string提示信息
                }

    """
    music_url = basic_url+"media/music"
    param = {"name": name}
    json_data = json.dumps(param)
    response = requests.delete(url=music_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_media_music_state():
    """获取机器人音乐播放状态

    Returns:
           Dict:
           e.g::
 
                {
                "code": integer (int32)返回码，0表示正常
                "data": {
                            "name": string 音乐名"SorrySorry.mp3",
                            "status": string音乐播放状态 "run"
                        },
                "msg": string提示信息 "success"
                }

    """
    music_url = basic_url+"media/music"
    response = requests.get(url=music_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def upload_media_music(filePath: str):
    """上传音乐文件

    上传到 /home/pi/Documents/music
    文件格式目前仅支持wav或者mp3

    Args:
        filePath(str):文件路径

    Returns:
           Dict:
           e.g::
 
                {
                    "code": integer (int32)返回码，0表示正常
                    "msg": string提示信息 "success"
                }

    """
    music_url = basic_url+"media/music"
    headers = {'Authorization': 'multipart/form-data'}
    files = {'file': open(filePath, 'rb')}
    response = requests.post(url=music_url, files=files, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def start_play_music(name: str = ""):
    """播放音乐

    Note:
        支持的音乐格式：wav和mp3

    Args:
        name(str): 音乐名称(可选)Default: SorrySorry.mp3

    Returns:
           Dict:
           e.g::
 
                {
                    "code": integer (int32)返回码，0表示正常
                    "msg": string提示信息 "success"
                }

    """
    return __control_media_music(operation='start',name=name)

def stop_play_music():
    """停止播放音乐

    Returns:
           Dict:
           e.g::
 
                {
                    "code": integer (int32)返回码，0表示正常
                    "msg": string提示信息 "success"
                }

    """
    return __control_media_music(operation='stop')


def __control_media_music(operation: str, name: str = ""):
    """播放/停止音乐

    Note:
        支持的音乐格式：wav和mp3

    Args:
        operation(str): 播放控制Default: start  start, stop
        name(str): 音乐名称(可选)Default: SorrySorry.mp3

    Returns:
           Dict:
           e.g::
 
                {
                    "code": integer (int32)返回码，0表示正常
                    "msg": string提示信息 "success"
                }

    """
    music_url = basic_url+"media/music"
    param = {"operation": operation}
    if (len(name) > 0):
        param["name"] = name
    json_data = json.dumps(param)
    response = requests.put(url=music_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_media_music_list():
    """获取音乐列表

    可以获得所有内置和用户上传的音乐列表

    Returns:
           Dict:
           e.g::
 
                {
                    "code": integer (int32)返回码，0表示正常
                    "data": {
                                "name": string 音乐名"SorrySorry.mp3",
                                "status": string音乐播放状态 "run"
                            },
                    "msg": string提示信息 "success"
                }
    """
    music_url = basic_url+"media/music/list"
    response = requests.get(url=music_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def sync_play_music(name: str = ""):
    """播放音乐，播放完成后返回

    Returns:
           BOOL: False 播放失败  True 播放成功

    """
    res = start_play_music(name)
    if res['code'] != 0:
        logging.error("play music failed error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return False
    coroutine = __wait_result_music(name = name, start_time = None, getFuc = get_media_music_state)
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    # Success Example
    return True


####motions####


def delete_motion(name: str):
    """删除动作文件

    删除在/home/pi/Documents/motions目录下的用户文件

    Args:
        name(str):要删除动作文件名称，不包括hts尾部

    Returns:
           Dict:
           e.g::
 
                {
                    "code": integer (int32)返回码，0表示正常
                    "data": {},
                    "msg": string提示信息 "success"
                }

    """
    motions_url = basic_url+"motions"
    param = {"name": name}
    json_data = json.dumps(param)
    response = requests.delete(
        url=motions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_current_motion_play_state():
    """获得当前动作文件执行状态

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {
                                "name": "",
                                "status": "idle"
                                "timestamp": 1551838515
                            },
                    "msg": "success"
                }

    """
    motions_url = basic_url+"motions"
    response = requests.get(url=motions_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_current_layer_motion_play_state():
    """获得当前动作文件执行状态

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": [{
                                "name": "",
                                "status": "idle"
                                "timestamp": 1551838515
                            }],
                    "msg": "success"
                }

    """
    motions_url = basic_url+"motions/all"
    response = requests.get(url=motions_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def __control_motion_play_state(operation: str = "start", name: str = "reset", direction: str = "", speed: str = "normal", repeat: int = 1, timestamp: int = 0, version: str = "v1"):
    """机器人动作控制

    Args:
        operation(str):运动控制Default: start    Enum: start, pause, resume, stop
        name(str): 动作名称
        direction(str):
        repeat(int): 
        speed(str): 
        timestamp(int):(int64)时间戳, Unix标准时间

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                        {
                            total_time:integer (int32)运行完成需要的时间（单位ms）
                        }
                    msg:string提示信息
                }

    """
    motion_url = basic_url+"motions"
    param = {"operation": operation, "motion":
             {"name": name, "repeat": repeat, "speed": speed}, "timestamp": timestamp,"version": version}
    if(len(direction) != 0):
        param["motion"]["direction"] = direction
    json_data = json.dumps(param)
    response = requests.put(url=motion_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def start_play_motion(name: str = "reset", direction: str = "", speed: str = "normal", repeat: int = 1, timestamp: int = 0,version: str="v1"):
    """开始执行动作

    Args:
        name(str): 动作名称 除了默认值和用户上传的动作还可以使用以下值：raise | crouch | stretch | come on | wave | bend | walk | turn around | head | bow
        direction(str):当name 是 "raise | stretch | come on | wave" 时, "direction" 可选项为:left | right | both，
                       当name 是 "bend | turn around" 时, "direction" 可选项为:left|right
                       当name 是 "walk", "direction" 时, "direction" 可选项为:forward | backward | left | right
                       当name 是 "head", "direction" 时, "direction" 可选项为:forward | left | right
        repeat(int): 重复次数  1 - 100
        speed(str): 动作执行速度，可选项（very slow,slow,normal,fast,very fast） 
        timestamp(int):(int64)时间戳, Unix标准时间
        version(str):动作执行类型，可选择：v1表示原来的hts动作，v2表示新的动作分层layer动作

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                        {
                            total_time:integer (int32)运行完成需要的时间（单位ms）
                        }
                    msg:string提示信息
                }

    """
    return __control_motion_play_state(operation = "start", name = name, direction = direction, speed = speed, repeat = repeat, timestamp = timestamp, version = version)

def pause_play_motion(name: str = "",timestamp: int = 0,version: str ="v1"):
    """暂停动作执行

    Args:
        name(str): 需要暂停的动作名称,默认为""，表示暂停所有动作
        timestamp(int):(int64)时间戳, Unix标准时间
        version(str):动作执行类型，可选择：v1表示原来的hts动作，v2表示新的动作分层layer动作
    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                        {
                            total_time:integer (int32)运行完成需要的时间（单位ms）
                        }
                    msg:string提示信息
                }

    """
    return __control_motion_play_state(name = name,operation = "pause",timestamp = timestamp,version = version)

def resume_play_motion(name: str = "",timestamp: int = 0,version: str ="v1"):
    """恢复动作执行

    Args:
        name(str): 需要恢复的动作名称,默认为""，表示恢复所有动作。
        timestamp(int):(int64)时间戳, Unix标准时间
        version(str):动作执行类型，可选择：v1表示原来的hts动作，v2表示新的动作分层layer动作
    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                        {
                            total_time:integer (int32)运行完成需要的时间（单位ms）
                        }
                    msg:string提示信息
                }

    """
    return __control_motion_play_state(name = name,operation = "resume",timestamp = timestamp,version = version)

def stop_play_motion(name: str = "",timestamp: int = 0,version: str ="v1"):
    """停止动作执行

    Args:
        name(str): 需要停止的动作名称,默认为""，表示停止所有动作。
        timestamp(int):(int64)时间戳, Unix标准时间
        version(str):动作执行类型，可选择：v1表示原来的hts动作，v2表示新的动作分层layer动作
    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                        {
                            total_time:integer (int32)运行完成需要的时间（单位ms）
                        }
                    msg:string提示信息
                }

    """
    return __control_motion_play_state(name = name,operation = "stop",timestamp = timestamp,version = version)


def sync_play_motion(name: str = "reset", direction: str = "", speed: str = "normal", repeat: int = 1,version: str = "v1"):
    """开始执行动作，执行完成后返回

    Args:
        name(str): 动作名称 除了默认值和用户上传的动作还可以使用以下值：raise | crouch | stretch | come on | wave | bend | walk | turn around | head | bow
        direction(str):当name 是 "raise | stretch | come on | wave" 时, "direction" 可选项为:left | right | both，
                       当name 是 "bend | turn around" 时, "direction" 可选项为:left|right
                       当name 是 "walk", "direction" 时, "direction" 可选项为:forward | backward | left | right
                       当name 是 "head", "direction" 时, "direction" 可选项为:forward | left | right
        repeat(int): 重复次数  1 - 100
        speed(str): 动作执行速度，可选项（very slow,slow,normal,fast,very fast）
        version(str):动作执行类型，可选择：v1表示原来的hts动作，v2表示新的动作分层layer动作

    Returns:
           BOOL:True 执行成功   False 执行失败

    """
    t = int(time.time() * 1000)
    res = start_play_motion(direction = direction, speed = speed, repeat = repeat,name = name, timestamp = t, version = version)
    if res['code'] != 0:
        logging.error("play motion failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return False
    if version == "v1":
        coroutine = __wait_result_motion(name = name, start_time = t, getFuc = get_current_motion_play_state)
    elif version == "v2":
        coroutine = __wait_result_layer_motion(name = name, start_time = t, getFuc = get_current_layer_motion_play_state)
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return True



def upload_motion(filePath: str):
    """上传动作文件

    上传动作文件到 /home/pi/Documents/motions 目录

    Args:
        file(str):hts文件的路径(包含名称).

    Returns:
           Dict:
           e.g::
 
                {
                    "code": integer (int32)返回码，0表示正常
                    "data": {},
                    "msg": string提示信息 "success"
                }

    """
    motions_url = basic_url+"motions"
    headers = {'Authorization': 'multipart/form-data'}
    files = {'file': open(filePath, 'rb')}
    response = requests.post(url=motions_url, files=files, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_motion_list_value():
    """获取动作文件列表

    Returns:
           List:
           e.g::              
    [A, B, C, D, E, F ,G]
    """
    motions_url = basic_url+"motions/list"
    response = requests.get(url=motions_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return []
    motion = []
    for item in res['data']['system_hts_motions']:
        motion.append(item['name'].rsplit('.', 1)[0])
    for item in res['data']['system_layers_motions']:
        motion.append(item['name'].rsplit('.', 1)[0])
    for item in res['data']['user_hts_motions']:
        motion.append(item['name'].rsplit('.', 1)[0])
    for item in res['data']['user_layers_motions']:
        motion.append(item['name'].rsplit('.', 1)[0])
    return motion

def get_motion_list():
    """获取动作文件列表

    Returns:
           Dict:
           e.g::
                {
                    "code": 0,
                    "data": {
                    "system_hts_motions": [
      {
        "music": true,
        "name": "A"
      },
      {
        "music": false,
        "name": "B"
      }
    ],
    "system_layers_motions": [
      {
        "music": true,
        "name": "C"
      },
      {
        "music": false,
        "name": "D"
      }
    ],
    "user_hts_motions": [
      {
        "music": true,
        "name": "E"
      },
      {
        "music": false,
        "name": "F"
      }
    ],
    "user_layers_motions": [
      {
        "music": true,
        "name": "G"
      },
      {
        "music": false,
        "name": "H"
      }
    ]
                    },
                    "msg": "Success"
                }

    """
    motions_url = basic_url+"motions/list"
    response = requests.get(url=motions_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def control_motion_gait(speed_v: int = 0, speed_h: int = 0, steps: int = 0, period: int = 1, wave: bool = False):
    """机器人步态动作控制

    Args:
        period : integer (int32) 取值 【0~5】当period = 0，表示停止步态。
        speed_v : integer (int32) 前后垂直行走速度，取值【-5~5】。
        speed_h : integer (int32) 左右水平行走速度，取值【-5~5】。
        steps: integer (int32) 总步数值，大于零的正整数。当steps =0时，代表10亿这样一个极大值。这个也是它的默认值。
        wave: bool 表示是否开启手臂摆动。取值true、false。

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    msg:string提示信息
                }

    """
    motion_url = basic_url + "motions/gait"
    timestamp = int(time.time()*1000)
    param = {
        "speed_v": speed_v,
        "speed_h": speed_h,
        "steps": steps,
        "period": period,
        "timestamp": timestamp,
        "wave": wave
    }
    json_data = json.dumps(param)
    response = requests.put(url=motion_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_motion_gait_state():
    """获取机器人步态执行状态

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {
                                "status": 8,
                                "timestamp": 0
                            },
                    "msg": "Success"
                }

    Note:
        status字段说明：

        0 --> Start to enter gait ready state.

        1 --> Already enter gait ready state.

        2 --> Start to walk.

        3 --> Reach the maximum steps.

        4 --> Start to entery gait stop state.

        5 --> Already enter gait stop state.

        6 --> Start to exit gait ready state.

        7 --> Already exit gait ready state.

        8 --> Idle state(no gait task).


    """
    motion_url = basic_url + "motions/gait"
    response = requests.get(url=motion_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def exit_motion_gait():
    """退出机器人步态执行

    机器人将从下蹲转为站立复位状态

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    motion_url = basic_url + "motions/gait"
    payload = {"timestamp": 0}
    response = requests.delete(url=motion_url, headers=headers,data = json.dumps(payload))
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def sync_do_motion_gait(speed_v: int = 0, speed_h: int = 0, steps: int = 0, period: int = 1, wave: bool = False):
    """机器人步态动作控制,执行完成后返回

    Args:
        period : integer (int32) 取值 【0~5】当period = 0，表示停止步态。
        speed_v : integer (int32) 前后垂直行走速度，取值【-5~5】。
        speed_h : integer (int32) 左右水平行走速度，取值【-5~5】。
        steps: integer (int32) 总步数值，大于零的正整数。当steps =0时，代表10亿这样一个极大值。这个也是它的默认值。
        wave: bool 表示是否开启手臂摆动。取值true、false。

    Returns:
           BOOL:False 执行失败  True 执行完成

    """
    # No stand up, since we could do multiple times
    t = int(time.time() * 1000)
    res = control_motion_gait(speed_v = speed_v, speed_h = speed_h, steps = steps, period = period, wave = wave)
    if res['code'] != 0:
        logging.error("do motion gait failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return False
    coroutine = __wait_result_gait(start_time=t, type='start', getFuc=get_motion_gait_state)
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return True
####aprilTag
def get_aprilTag_recognition_status():
    """查询aprilTag 识别状态

    Returns:
           Dict: aprilTag 识别状态
           e.g::

                {
                    "status":"run",
                    "data":{
                        "AprilTagStatus":
                        [
                            {"id":10,"orientation-x":2.00,"orientation-y":2.00,"orientation-z":2.00,"orientation-w":2.00,"postion-x":1,"postion-y":1,"postion-z":1},
                            {"id":11,"orientation-x":2.00,"orientation-y":2.00,"orientation-z":2.00,"orientation-w":2.00,"postion-x":1,"postion-y":1,"postion-z":1},
                        ]
                    },
                    "code":0,
                    "msg":""
                }
    """
    servos_url = basic_url+"visions/aprilTag"
    response = requests.get(url=servos_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return -1
    return res

PaprilTagStream = None
def start_aprilTag_recognition(tags: List,enableStream: bool = False):
    """开启aprilTag识别
    Args:
        tags:需要识别的apriltag id 及 size
        enableStream:是否需要打开视频流
    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    msg:string提示信息
                    stream_url:string 视频流地址
                }
    """
    motion_url = basic_url + "visions/aprilTag"
    # aprilTags = []
    # for key,value in tags.items():
    #     tag = {"id":key,"size":value}
    #     aprilTags.append(tag)
    param = {
                "operation": "start",
                "tags":tags,
                "remote_stream_enable":enableStream
            }
    json_data = json.dumps(param)
    response = requests.put(url=motion_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    global PaprilTagStream
    global ip
    streamUrl = ""
    if not (PaprilTagStream is None) and PaprilTagStream.is_alive():
       return res
    if (res["code"] == 0 or res['code'] == 20003) and enableStream:
        streamUrl = res["streamUrl"]
        if res['code'] == 20003:
            if ip != "127.0.0.1":
                port = res["streamUrl"][7:][res["streamUrl"][7:].find(':') + 1:]
                streamUrl = "http://" + ip + ":" + port
        PaprilTagStream=Process(target=__openStreamWindow,args=('aprilTag',streamUrl,ip)) #必须加,号
        PaprilTagStream.start()
    return res

def __openStreamWindow(windowName,url,ip_addr:str = ip):
    camera = cv2.VideoCapture(url)
    ret = camera.isOpened()
    counter = 0
    if not ret:
        print('Unable to accquire data')
        cv2.destroyAllWindows()
        cv2.waitKey(1)
        if windowName == "aprilTag":
            yan_api_init(ip_addr)
            __stop_aprilTag_recognition()
        return
    try:
        while True:
            ret, frame = camera.read()
            if not ret:
                print('fail to read data, ip may not correct')
                if counter > 2:
                    print('maximum 2 retry occur, exit...')
                    cv2.destroyAllWindows()
                    cv2.waitKey(1)
                    camera.release()
                    exit(0)
                else:
                    # cv2.destroyAllWindows()
                    print('retry  VideoCapture')
                    cv2.waitKey(1)
                    camera.release()
                    counter += 1
                    camera = cv2.VideoCapture(url)
                    continue
            else:
                counter = 0
            cv2.imshow(windowName, frame)
            cv2.waitKey(1)
            if cv2.getWindowProperty(windowName, cv2.WND_PROP_VISIBLE) <= 0 and sys.platform != "darwin":
               if windowName == "aprilTag":
                    yan_api_init(ip_addr)
                    __stop_aprilTag_recognition()
               break
    except:
        print('program crash')
        if windowName == "aprilTag":
            yan_api_init(ip_addr)
            __stop_aprilTag_recognition()
        cv2.destroyAllWindows()
        cv2.waitKey(1)

def __stop_aprilTag_recognition():
    """关闭aprilTag识别
    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    msg:string提示信息
                }
    """
    motion_url = basic_url + "visions/aprilTag"
    timestamp = int(time.time()*1000)
    param = {
            "operation": "stop",
            "timestamp": timestamp
            }
    json_data = json.dumps(param)
    response = requests.put(url=motion_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def stop_aprilTag_recognition():
    """关闭aprilTag识别
    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    msg:string提示信息
                }
    """
    res = __stop_aprilTag_recognition()
    global PaprilTagStream
    if not (PaprilTagStream is None) and PaprilTagStream.is_alive():
        PaprilTagStream.terminate()
        PaprilTagStream = None
    return res
####QR code
def get_QR_code_recognition_status():
    """查询二维码 识别状态

    Returns:
           Dict: 二维码 识别状态
           e.g::

                {
                    "status":"run",
                    "data":{
                         "content":""
                    }
                    "code":0,
                    "msg":""
                }
    """
    servos_url = basic_url+"visions/QR"
    response = requests.get(url=servos_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
    return res

PqrStream = None
def start_QR_code_recognition(enableStream: bool = False):
    """开启二维码识别
    Args:
        timeOut:最大等待时间 单位（秒）传0表示永久等待
    Returns:
        Dict:
           e.g::
                {
                    code:integer (int32)返回码，0表示正常
                    msg:string提示信息
                }
        
    """
    motion_url = basic_url + "visions/QR"
    param = {
              "operation": "start",
              "remote_stream_enable":enableStream
            }
    json_data = json.dumps(param)
    response = requests.put(url=motion_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    global PqrStream
    global ip
    streamUrl = ""
    if not (PqrStream is None) and PqrStream.is_alive():
       PqrStream.terminate()
       PqrStream = None
    if (res["code"] == 0 or res['code'] == 20003) and enableStream:
        streamUrl = res["streamUrl"]
        if res['code'] == 20003:
            if ip != "127.0.0.1":
                port = res["streamUrl"][7:][res["streamUrl"][7:].find(':') + 1:]
                streamUrl = "http://" + ip + ":" + port
        PqrStream=Process(target=__openStreamWindow,args=('qrCode',streamUrl)) #必须加,号
        PqrStream.start()
    return res

def stop_QR_code_recognition():
    """关闭二维码识别
    Returns:
           Dict:
           e.g::
                {
                    code:integer (int32)返回码，0表示正常
                    msg:string提示信息
                }
    """
    motion_url = basic_url + "visions/QR"
    param = {
                "operation": "stop",
            }
    json_data = json.dumps(param)
    response = requests.put(url=motion_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    global PqrStream
    if not (PqrStream is None) and PqrStream.is_alive():
        PqrStream.terminate()
        PqrStream = None
    return res

def sync_do_QR_code_recognition(timeOut:int = 8):
    """开启二维码识别,识别到或超时后返回
    Args:
        timeOut:最大等待时间 单位（秒）小于等于0表示永久等待
    Returns:
           Dict:
           e.g::

                {
                    code: integer 返回码：0表示正常
                    content: string 识别到的内容
                    status: string 状态
                    msg: string 提示信息
                }
    """
    res = start_QR_code_recognition(True)
    if not (res['code'] == 0 or res['code'] == 20003):
        logging.error("start QR code recognition failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return res
    coroutine = __wait_result_QR(get_QR_code_recognition_status,timeOut,True)
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return tasks.result()

####ObjectTracking####
def get_object_tracking_status():
    """获取物体跟踪工作状态

    Returns:
           e.g::

                {
                    "status":"run",
                    "code":0,
                    "msg":"Success"
                }
    """
    object_tracking_url = basic_url+"visions/object/tracking"
    response = requests.get(url=object_tracking_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
    return res

def start_object_tracking(name: str = "", width: int = 0, height: int = 0):
    """开启物体跟踪
    Args:
        name: 指定特定跟踪物体， wukong, yanshee, rubik_cube, orange, red_apple, 可不指定
        width: 在指定特定跟踪物体情况下指定摄像头视野横截面宽度, 单位m，可不指定, 使用系统默认
        height: 在指定特定跟踪物体情况下指定摄像头视野横截面高度, 单位m, 可不指定, 使用系统默认
        在跟踪物体未指定下将跟踪识别出的最大物体, 使用默认横截面宽高 (正常与正面物体)

        默认宽高值:
        yanshee 0.1 0.365
        wukong 0.06 0.24
        orange 0.075 0.085
        red apple 0.08 0.075
        rubik cube 0.057 0.057

    Returns:
           e.g::

                {
                    "data":{},
                    "code":0,
                    "msg":"Success"
                }
    """
    object_tracking_url = basic_url+"visions/object/tracking"
    if name != "":
        msg = {"operation":"start", "name":name, "width":width, "height": height}
    else:
        msg = {"operation":"start"}
    response = requests.put(url=object_tracking_url, data = json.dumps(msg), headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
    return res

def stop_object_tracking():
    """关闭物体跟踪
    Returns:
           e.g::

                {
                    "data":{},
                    "code":0,
                    "msg":"Success"
                }
    """
    object_tracking_url = basic_url+"visions/object/tracking"
    msg = {"operation":"stop"}
    response = requests.put(url=object_tracking_url, data = json.dumps(msg), headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
    return res


def config_object_tracking(track_timeout: int, detect_timeout: int):
    """配置物体跟踪
    Args:
        track_timeout: 跟丢物体超时时间, 单位为s, 1 - 100，系统默认为 5s
        detect_timeout: 无法检测到物体超时时间, 单位为s, 0 - 100, 0 为不限制检测时间, 系统默认为10s
        配置物体跟踪需物体跟踪未开启
    Returns:
           e.g::

                {
                    "data":{},
                    "code":0,
                    "msg":"Success"
                }
    """
    object_tracking_config_url = basic_url+"visions/object/tracking/config"
    msg = {"track_timeout":track_timeout, "detect_timeout":detect_timeout}
    response = requests.put(url=object_tracking_config_url, data = json.dumps(msg), headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
    return res

####Servos####
def get_servo_angle_value(name:str):
    """查询舵机角度值

    一次可以查询一个舵机角度值

    Args:
        name:String机器人舵机名称

    Note:
        可用参数说明

        ("RightShoulderRoll","right_shoulder_roll"), #servo No.1

        ("RightShoulderFlex","right_shoulder_flex"), #servo No.2

        ("RightElbowFlex","right_elbow_flex"),       #servo No.3

        ("LeftShoulderRoll","left_shoulder_roll"),   #servo No.4

        ("LeftShoulderFlex","left_shoulder_flex"),   #servo No.5

        ("LeftElbowFlex","left_elbow_flex"),         #servo No.6

        ("RightHipLR","right_hip_lr"),               #servo No.7

        ("RightHipFB","right_hip_fb"),               #servo No.8

        ("RightKneeFlex","right_knee_flex"),         #servo No.9

        ("RightAnkleFB","right_ankle_fb"),           #servo No.10

        ("RightAnkleUD","right_ankle_ud"),           #servo No.11

        ("LeftHipLR","left_hip_lr"),                 #servo No.12

        ("LeftHipFB","left_hip_fb"),                 #servo No.13

        ("LeftKneeFlex","left_knee_flex"),           #servo No.14

        ("LeftAnkleFB","left_ankle_fb"),             #servo No.15

        ("LeftAnkleUD","left_ankle_ud"),             #servo No.16

        ("NeckLR","neck_lr")                         #servo No.17

    Returns:
           int:舵机角度值

    Examples:
        查询2号舵机
        >>> res = YanAPI.get_servo_angle_value("RightShoulderFlex")

    """
    servos_url = basic_url+"servos/angles"
    params = {'names':[name]}
    response = requests.get(url=servos_url, headers=headers, params=params)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return -1
    values =  res["data"].popitem()
    return values[1]



def get_servos_angles(names: List[str]):
    """查询舵机角度值

    一次可以查询一个或者多个舵机角度值

    Args:
        names: list[String]机器人舵机名称列表

    Note:
        可用参数说明

        ("RightShoulderRoll","right_shoulder_roll"), #servo No.1

        ("RightShoulderFlex","right_shoulder_flex"), #servo No.2

        ("RightElbowFlex","right_elbow_flex"),       #servo No.3

        ("LeftShoulderRoll","left_shoulder_roll"),   #servo No.4

        ("LeftShoulderFlex","left_shoulder_flex"),   #servo No.5

        ("LeftElbowFlex","left_elbow_flex"),         #servo No.6

        ("RightHipLR","right_hip_lr"),               #servo No.7

        ("RightHipFB","right_hip_fb"),               #servo No.8

        ("RightKneeFlex","right_knee_flex"),         #servo No.9

        ("RightAnkleFB","right_ankle_fb"),           #servo No.10

        ("RightAnkleUD","right_ankle_ud"),           #servo No.11

        ("LeftHipLR","left_hip_lr"),                 #servo No.12

        ("LeftHipFB","left_hip_fb"),                 #servo No.13

        ("LeftKneeFlex","left_knee_flex"),           #servo No.14

        ("LeftAnkleFB","left_ankle_fb"),             #servo No.15

        ("LeftAnkleUD","left_ankle_ud"),             #servo No.16

        ("NeckLR","neck_lr")                         #servo No.17

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                                                {
                            RightShoulderRoll:integer maximum:180     1号舵机
                            RightShoulderFlex: integer maximum:180    2号舵机
                            RightElbowFlex: integer maximum:180       3号舵机
                            LeftShoulderRoll: integer maximum:180     4号舵机
                            LeftShoulderFlex:integer maximum:180      5号舵机
                            LeftElbowFlex: integer maximum:180        6号舵机
                            RightHipLR: integer maximum:180           7号舵机
                            RightHipFB: integer maximum:180           8号舵机
                            RightKneeFlex:integer maximum:180         9号舵机
                            RightAnkleFB:integer maximum:180          10号舵机
                            RightAnkleUD: integer maximum:180         11号舵机
                            LeftHipLR: integer maximum:180            12号舵机
                            LeftHipFB: integer maximum:180            13号舵机
                            LeftKneeFlex:integer maximum:180          14号舵机
                            LeftAnkleFB: integer maximum:180          15号舵机
                            LeftAnkleUD:integer maximum:180           16号舵机
                            NeckLR: integer minimum:45 maximum:135    17号舵机
                        }
                    msg: string提示信息
                }

    Examples:
        查询2号舵机和17号舵机角度

        >>> res = YanAPI.get_servos_angles(["RightShoulderFlex","NeckLR"])
            print (res["data"])

    """
    servos_url = basic_url+"servos/angles"
    params = {'names': names}
    response = requests.get(url=servos_url, headers=headers, params=params)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def set_servos_angles(angles: Dict[str, int], runtime: int = 200):
    """设置舵机角度值

    一次可以设置一个或者多个舵机角度值

    Args:
        angles(map) : {servoName:angle}  servoName舵机名，angel 角度：【0-180】
        runtime(int): minimum:200 maximum:4000 运行时间，单位ms

    Note:
        ID1/2/3/4/5/6/9/10/14/15舵机可运行角度范围为0-180,超出范围运动存在风险.

        ID7舵机可运行角度范围为0-120,超出范围运动存在风险.

        ID8舵机可运行角度范围为10-180,超出范围运动存在风险.

        ID11舵机可运行角度范围为65-180,超出范围运动存在风险.

        ID12舵机可运行角度范围为60-180,超出范围运动存在风险.

        ID13舵机可运行角度范围为0-170,超出范围运动存在风险.

        ID16舵机可运行角度范围为0-115,超出范围运动存在风险.

        ID17舵机可运行角度范围为15-165,超出范围运动存在风险.

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                                                {
                            RightShoulderRoll:boolean 1号舵机，true表示设置成功，false表示失败
                            RightShoulderFlex:boolean 2号舵机，true表示设置成功，false表示失败
                            RightElbowFlex:boolean 3号舵机，true表示设置成功，false表示失败
                            LeftShoulderRoll:boolean 4号舵机，true表示设置成功，false表示失败
                            LeftShoulderFlex:boolean 5号舵机，true表示设置成功，false表示失败
                            LeftElbowFlex:boolean 6号舵机，true表示设置成功，false表示失败
                            RightHipLR:boolean 7号舵机，true表示设置成功，false表示失败
                            RightHipFB:boolean 8号舵机，true表示设置成功，false表示失败
                            RightKneeFlex:boolean 9号舵机，true表示设置成功，false表示失败
                            RightAnkleFB:boolean 10号舵机，true表示设置成功，false表示失败
                            RightAnkleUD:boolean 11号舵机，true表示设置成功，false表示失败
                            LeftHipLR:boolean 12号舵机，true表示设置成功，false表示失败
                            LeftHipFB: boolean 13号舵机，true表示设置成功，false表示失败
                            LeftKneeFlex:boolean 14号舵机，true表示设置成功，false表示失败
                            LeftAnkleFB:boolean 15号舵机，true表示设置成功，false表示失败
                            LeftAnkleUD:boolean 16号舵机，true表示设置成功，false表示失败
                            NeckLR:boolean 17号舵机，true表示设置成功，false表示失败
                        }
                    msg:string提示信息
                }

    """
    servos_url = basic_url+"servos/angles"
    param = {"angles": angles, "runtime": runtime}
    json_data = json.dumps(param)
    response = requests.put(url=servos_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def set_servos_angles_layers(data: Dict[str,Dict[int,int]]):
    """设置舵机角度值

    一次可以设置一个或者多个舵机角度值

    Args:
        angles(map) : {servoName:{angle,isNeedBessel,runtime}}  servoName舵机名，angel 角度：【0-180】,isNeedBessel:是否需要变速运动(贝塞尔曲线)，runtime:minimum:200 maximum:4000 运行时间，单位ms

    Note:
        ID1/2/3/4/5/6/9/10/14/15舵机可运行角度范围为0-180,超出范围运动存在风险.

        ID7舵机可运行角度范围为0-120,超出范围运动存在风险.

        ID8舵机可运行角度范围为10-180,超出范围运动存在风险.

        ID11舵机可运行角度范围为65-180,超出范围运动存在风险.

        ID12舵机可运行角度范围为60-180,超出范围运动存在风险.

        ID13舵机可运行角度范围为0-170,超出范围运动存在风险.

        ID16舵机可运行角度范围为0-115,超出范围运动存在风险.

        ID17舵机可运行角度范围为15-165,超出范围运动存在风险.

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                                                {
                        }
                    msg:string提示信息
                }

    """
    servos_url = basic_url+"servos/angles/layers"
    param = {"data": data}
    json_data = json.dumps(param)
    response = requests.put(url=servos_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def sync_set_servo_rotate(angles: Dict[str, int], runtime: int = 200):
    """设置舵机角度值,设置完成后返回

    Args:
        angles(map) : {servoName:angle}  servoName舵机名，angel 角度：【0-180】
        runtime(int): minimum:200 maximum:4000 运行时间，单位ms

    Note:
        ID1/2/3/4/5/6/9/10/14/15舵机可运行角度范围为0-180,超出范围运动存在风险.

        ID7舵机可运行角度范围为0-120,超出范围运动存在风险.

        ID8舵机可运行角度范围为10-180,超出范围运动存在风险.

        ID11舵机可运行角度范围为65-180,超出范围运动存在风险.

        ID12舵机可运行角度范围为60-180,超出范围运动存在风险.

        ID13舵机可运行角度范围为0-170,超出范围运动存在风险.

        ID16舵机可运行角度范围为0-115,超出范围运动存在风险.

        ID17舵机可运行角度范围为15-165,超出范围运动存在风险.

    Returns:
           BOOL:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                                                {
                            RightShoulderRoll:boolean1号舵机，true表示设置成功，false表示失败
                            RightShoulderFlex:boolean2号舵机，true表示设置成功，false表示失败
                            RightElbowFlex:boolean3号舵机，true表示设置成功，false表示失败
                            LeftShoulderRoll:boolean4号舵机，true表示设置成功，false表示失败
                            LeftShoulderFlex:boolean5号舵机，true表示设置成功，false表示失败
                            LeftElbowFlex:boolean6号舵机，true表示设置成功，false表示失败
                            RightHipLR:boolean7号舵机，true表示设置成功，false表示失败
                            RightHipFB:boolean8号舵机，true表示设置成功，false表示失败
                            RightKneeFlex:boolean9号舵机，true表示设置成功，false表示失败
                            RightAnkleFB:boolean10号舵机，true表示设置成功，false表示失败
                            RightAnkleUD:boolean11号舵机，true表示设置成功，false表示失败
                            LeftHipLR:boolean12号舵机，true表示设置成功，false表示失败
                            LeftHipFB: boolean13号舵机，true表示设置成功，false表示失败
                            LeftKneeFlex:boolean14号舵机，true表示设置成功，false表示失败
                            LeftAnkleFB:boolean15号舵机，true表示设置成功，false表示失败
                            LeftAnkleUD:boolean16号舵机，true表示设置成功，false表示失败
                            NeckLR:boolean17号舵机，true表示设置成功，false表示失败
                        }
                    msg:string提示信息
                }

    """
    res = set_servos_angles(angles = angles, runtime = runtime)
    if res['code'] != 0:
        logging.error("set servo failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return res
    coroutine = __wait_result_by_time(runtime / 1000) # ms --> s
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return res


def get_servos_mode(names: List[str]):
    """查询舵机工作模式

    一次可以查询一个或者多个舵机工作模式

    Args:
        names(list[str]):机器人舵机名称列表

    Returns:
           Dict:
           e.g::
 
                {
                    code (integer): Return code, 0 means success ,
                    data (ServosMode),
                    msg (string): Return message
                }

    Note:
       ServosMode 说明：

                LeftAnkleFB (string, optional): Servo 15 = ['work', 'program', 'unknown', 'nonsupport']

                LeftAnkleUD (string, optional): Servo 16 = ['work', 'program', 'unknown', 'nonsupport']

                LeftElbowFlex (string, optional): Servo 6 = ['work', 'program', 'unknown', 'nonsupport']

                LeftHipFB (string, optional): Servo 13 = ['work', 'program', 'unknown', 'nonsupport']

                LeftHipLR (string, optional): Servo 12 = ['work', 'program', 'unknown', 'nonsupport']

                LeftKneeFlex (string, optional): Servo 14 = ['work', 'program', 'unknown', 'nonsupport']

                LeftShoulderFlex (string, optional): Servo 5 = ['work', 'program', 'unknown', 'nonsupport']

                LeftShoulderRoll (string, optional): Servo 4 = ['work', 'program', 'unknown', 'nonsupport']

                NeckLR (string, optional): Servo 17 = ['work', 'program', 'unknown', 'nonsupport']

                RightAnkleFB (string, optional): Servo 10 = ['work', 'program', 'unknown', 'nonsupport']

                RightAnkleUD (string, optional): Servo 11 = ['work', 'program', 'unknown', 'nonsupport']

                RightElbowFlex (string, optional): Servo 3 = ['work', 'program', 'unknown', 'nonsupport']

                RightHipFB (string, optional): Servo 8 = ['work', 'program', 'unknown', 'nonsupport']

                RightHipLR (string, optional): Servo 7 = ['work', 'program', 'unknown', 'nonsupport']

                RightKneeFlex (string, optional): Servo 9 = ['work', 'program', 'unknown', 'nonsupport']

                RightShoulderFlex (string, optional): Servo 2 = ['work', 'program', 'unknown', 'nonsupport']

                RightShoulderRoll (string, optional): Servo 1 = ['work', 'program', 'unknown', 'nonsupport']

    Examples:
        >>> res = YanAPI.get_servos_mode(["NeckLR","RightShoulderFlex"])
           print(res)
           ==============
           {
                "code": 0,
                "data": {
                            "NeckLR": "nonsupport",
                            "RightShoulderFlex": "nonsupport"
                        },
                "msg": "success"
            }

    """
    servos_url = basic_url+"servos/mode"
    params = {"names": names}
    response = requests.get(url=servos_url, headers=headers, params=params)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def set_servos_mode(mode: str, servos: List[str]):
    """设置舵机工作模式

    包括两种模式：工作和可编程。舵机被设置成可编程状态之后，就变成掉电可回读状态。一次可以设置一个或者多个舵机。

    Args:
        mode(str): 包括 work 、program
        servos(list[str]): 机器人舵机名称

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {
                                "NeckLR": true,
                                "RightShoulderFlex": true
                            },
                    "msg": "success"
                }

    """
    servos_url = basic_url+"servos/mode"
    param = {"mode": mode, "servos": []}
    for i in range(len(servos)):
        param["servos"].append({"name": servos[i]})
    json_data = json.dumps(param)
    response = requests.put(url=servos_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

####Sensors####

def sensor_calibration(id: int):
    """传感器校准

    目前只支持运动传感器（gyro）校准

    Args:
        id(int):传感器的id值，1~127的值。

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    __set_sensors(operation="Calibrate",type = "gyro",id=id)

def __set_sensors(operation: str, id: int, type: str, value: int = 0):
    """传感器设置(校准或修改地址)

    目前只支持运动传感器（gyro）校准

    Args:
        operation(str):modify,修改传感器IIC地址。Calibrate,仅仅陀螺仪传感器支持校准。
        id(int):传感器的id值，1~127的值。
        type(str):传感器类型名,Default:gyro包括:"gyro", "infrared", "ultrasonic", "touch", "pressure"
        value(int):可选，默认为0，用来填写新的修改后的传感器IIC地址值（id值）。陀螺仪校准不需要填写。

    Note:
        目前出厂的传感器范围定义如下:

        Ultrasonic sensor 17~22;

        Infrared sensor 23~28;

        Touch sensor 29~34;

        Pressure Sensor 35~40;

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    sensors_url = basic_url+"sensors"
    param = {"operation": operation, "sensor":
             {"id": id, "type": type, "value": value}}
    json_data = json.dumps(param)
    response = requests.put(url=sensors_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_sensors_list_value():
    """获取所有传感器的列表(便利方法)

    Returns:
           List:
           e.g::

                [
                    type:string传感器名称 gyro, infrared, ultrasonic, environment, touch, pressure
                ]

    """
    sensor_url = basic_url+"sensors/list"
    response = requests.get(url=sensor_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return []
    sensors = res["data"]["sensors"]
    sensorsName = []
    for sensor in sensors:
        sensorName = sensor.get("type")
        if sensorName:
            sensorsName.append(sensorName)
    return sensorsName


def get_sensors_list():
    """获取所有传感器的列表

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {
                            sensors:
                                [
                                    {
                                        id: integer (int32) minimum:1 maximum:127传感器地址
                                        slot:integer (int32) minimum:1 maximum:6传感器槽位号
                                        type:string传感器名称 gyro, infrared, ultrasonic, environment, touch, pressure
                                        version: integer (int32)传感器版本号
                                    }
                                ]
                            },
                    "msg": "Success"
                }

    """
    sensor_url = basic_url+"sensors/list"
    response = requests.get(url=sensor_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_sensors_environment_value():
    """获取环境传感器值(便利方法)

    Returns:
        Dict:
        e.g::

             {
                id: integer (int32) minimum:1 maximum:127
                slot:integer (int32) minimum:1 maximum:6传感器槽位号
                temperature:integer (int32)温度值
                humidity: integer (int32)湿度值
                pressure: integer (int32)大气压力
            }
    """
    res = get_sensors_environment()
    if not __resIsSuccess(res):
         logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
         return "Failed to get environment sensor value."
    values = res["data"]["environment"]
    if len(values) == 0:
        return "not connected to sensor."
    return values[0]

def get_sensors_environment():
    """获取环境传感器值

    Note:
    使用此接口前，请先调用sensors/list接口来查看相应的传感器是否被检测到。

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    data:
                                                {
                            environment:[
                                                                                        {
                                                id: integer (int32) minimum:1 maximum:127
                                                slot:integer (int32) minimum:1 maximum:6传感器槽位号
                                                temperature:integer (int32)温度值
                                                humidity: integer (int32)湿度值
                                                pressure: integer (int32)大气压力
                                            }
                                        ]
                        }
                    msg:string提示信息
                }

    """
    sensor_url = basic_url+"sensors/environment"
    response = requests.get(url=sensor_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_sensors_gyro():
    """获取九轴陀螺仪运动传感器值

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                                                {
                            gyro:[
                                                                        {
                                        id: integer (int32) minimum:1 maximum:127
                                        gyro-x:number (float)
                                        gyro-y:number (float)
                                        gyro-z:number (float)
                                        accel-x:number (float)
                                        accel-y:number (float)
                                        accel-z:number
                                        compass-x:number (float)
                                        compass-y:number (float)
                                        compass-z:number (float)
                                        euler-x:number (float)
                                        euler-y:number (float)
                                        euler-z:number (float)
                                    }
                                ]
                        }
                    msg:string提示信息
                }

    """
    sensor_url = basic_url+"sensors/gyro"
    response = requests.get(url=sensor_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_sensors_infrared_value():
    """获取红外距离传感器值(便利方法)

    Returns:
        int:距离值，单位毫米
    """
    res = get_sensors_infrared()
    if not __resIsSuccess(res):
         logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
         return "Failed to get infrared distance sensor value."
    values = res["data"]["infrared"]
    if len(values) == 0:
        return "not connected to sensor."
    return values[0]["value"]

def get_sensors_infrared(id: List[int] = None, slot: List[int] = None):
    """获取红外距离传感器值

    Args:
        id(List[int]):传感器地址,可不填
        slot(List[int]):传感器槽位号,可不填

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                                                {
                            infrared:
                                    [
                                                                                {
                                            id: integer (int32) minimum:1 maximum:127传感器地址
                                            slot: integer (int32) minimum:1 maximum:6传感器槽位号
                                            value: integer (int32)距离值，单位毫米
                                        }
                                    ]
                        }
                    msg:string提示信息
                }

    """
    sensor_url = basic_url+"sensors/infrared"
    if (id != None) and (slot == None):
        params = {"id": id}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    elif (id == None) and (slot != None):
        params = {"slot": slot}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    elif (id != None) and (slot != None):
        params = {"id": id, "slot": slot}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    else:
        response = requests.get(url=sensor_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_sensors_pressure_value():
    """读取机器人压力传感器值(便利方法)

    Returns:
        int:压力传感器值
    """
    res = get_sensors_pressure()
    if not __resIsSuccess(res):
         logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
         return "Failed to get pressure sensor value."
    values = res["data"]["pressure"]
    if len(values) == 0:
        return "not connected to sensor."
    return values[0]["value"]

def get_sensors_pressure(id: List[int] = None, slot: List[int] = None):
    """读取机器人身上的压力传感器值

    Args:
        id(List[int]):传感器地址,可不填
        slot(List[int]):传感器槽位号,可不填

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                                                {
                            pressure:
                                    [ 
                                        {
                                            id: integer (int32) minimum:1 maximum:127传感器地址
                                            slot: integer (int32) minimum:1 maximum:6传感器槽位号
                                            value: integer (int32)压力值，单位：牛
                                        }
                                    ]
                        }
                    msg:string提示信息
                }

    """
    sensor_url = basic_url+"sensors/pressure"
    if (id != None) and (slot == None):
        params = {"id": id}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    elif (id == None) and (slot != None):
        params = {"slot": slot}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    elif (id != None) and (slot != None):
        params = {"id": id, "slot": slot}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    else:
        response = requests.get(url=sensor_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_sensors_touch_value():
    """获取触摸传感器值(便利方法)

    Returns:
        int:触摸传感器值 0 （未触摸）1 （触摸btn1）2 （触摸btn2）3 （触摸两边）
    """
    res = get_sensors_touch()
    if not __resIsSuccess(res):
         logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
         return "Failed to get touch sensor value."
    values = res["data"]["touch"]
    if len(values) == 0:
        return "not connected to sensor."
    return values[0]["value"]

def get_sensors_touch(id: int = None, slot: List[int] = None):
    """获取触摸传感器值

    Args:
        id(array[Integer]): (int32) 传感器地址，可不填
        slot(array[Integer]): (int32) 传感器槽位号，可不填

    Returns:
           Dict:
           e.g::
 
                {
                    code:integer (int32)返回码，0表示正常
                    data:
                                                {
                            touch:
                                [ 
                                    {
                                        id: integer (int32) minimum:1 maximum:127传感器地址
                                        slot: integer (int32) minimum:1 maximum:6传感器槽位号
                                        value: integer (int32) 0 （未触摸）1 （触摸btn1）2 （触摸btn2）3 （触摸两边）
                                    }
                                ]
                        }
                    msg:string提示信息
                }

    """
    sensor_url = basic_url+"sensors/touch"
    if (id != None) and (slot == None):
        params = {"id": id}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    elif (id == None) and (slot != None):
        params = {"slot": slot}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    elif (id != None) and (slot != None):
        params = {"id": id, "slot": slot}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    else:
        response = requests.get(url=sensor_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def get_sensors_ultrasonic_value():
    """获取超声传感器值(便利方法)

    Returns:
        int:超声传感器读数
    """
    res = get_sensors_ultrasonic()
    if not __resIsSuccess(res):
         logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
         return "Failed to get ultrasonic sensor value."
    values = res["data"]["ultrasonic"]
    if len(values) == 0:
        return "not connected to sensor."
    return values[0]["value"]

def get_sensors_ultrasonic(id=None, slot=None):
    """获取超声传感器值

    Args:
        id(array[Integer]): (int32) 传感器地址，可不填
        slot(array[Integer]): (int32) 传感器槽位号，可不填

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    data:
                                                {
                            ultrasonic:
                                      [
                                        {
                                            id: integer (int32) minimum:1 maximum:127传感器地址
                                            slot:integer (int32) minimum:1 maximum:6传感器槽位号
                                            value: integer (int32)距离值，单位毫米
                                        }
                                      ]
                        }
                    msg:string提示信息
                }

    """
    sensor_url = basic_url+"sensors/ultrasonic"
    if (id != None) and (slot == None):
        params = {"id": id}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    elif (id == None) and (slot != None):
        params = {"slot": slot}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    elif (id != None) and (slot != None):
        params = {"id": id, "slot": slot}
        response = requests.get(url=sensor_url, headers=headers, params=params)
    else:
        response = requests.get(url=sensor_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

####Voice####


def stop_voice_asr():
    """停止语音识别服务

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    voice_url = basic_url+"voice/asr"
    response = requests.delete(url=voice_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_voice_asr_state():
    """获取语义理解工作状态

    Returns:
           Dict:
           e.g::
 
                {
                    code: 0,
                    status:string  idle 非执行状态 run 正在运行
                    timestamp:integer (int32)时间戳, Unix标准时间
                    data:
                                                {
                            语音返回数据
                        }
                    msg: string提示信息
                }

    """
    voice_url = basic_url+"voice/asr"
    response = requests.get(url=voice_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    ret=get_robot_language()
    if ret["data"]["language"]=="ko" :
        return res
    dataStr = res["data"].strip(b'\x00'.decode())
    res["data"] = json.loads(dataStr)
    return res


def start_voice_asr(continues=False, timestamp=0):
    """开始语义理解

    当语义理解(单次/多次)处于工作状态时，需要先停止当前的语义理解。

    Args:
        continues:boolean是否进行连续语意识别, 布尔值, true 需要， false不需要, 默认为false
        timestamp:integer (int32)时间戳, Unix标准时间

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    voice_url = basic_url+"voice/asr"
    param = {"continues": continues, "timestamp": timestamp}
    json_data = json.dumps(param)
    response = requests.put(url=voice_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def sync_do_voice_asr_value():
    """执行一次语义理解并获得返回结果(便利方法)

    Returns:
          Dict:
          e.g::

            {
                question:""#识别的内容
                answer:""  #回答的内容
            }

    """
    timestamp = int(time.time())
    start_voice_asr(timestamp=timestamp)
    coroutine = __wait_result(timestamp,get_voice_asr_state)
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    res = tasks.result()
    if  not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return None
    ret=get_robot_language()
    if ret["data"]["language"]=="ko" :
        return res["data"]
    return RobotAsrResult(res["data"]).retDict


def sync_do_voice_asr():
    """执行一次语义理解并获得返回结果

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    status:string idle 非执行状态run 正在运行
                    timestamp:integer (int32)时间戳, Unix标准时间
                    data:
                        {
                            语义理解返回内容数据
                        }
                    msg: string提示信息
                }

    """
    timestamp = int(time.time())
    start_voice_asr(timestamp=timestamp)
    coroutine = __wait_result(timestamp, get_voice_asr_state)
    # result = asyncio.run(coroutine)

    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return tasks.result()


def delete_voice_asr_offline_syntax(grammar: str):
    """删除指定离线语法名称下的所有配置

    可以先获取系统所有的离线语法名称，请注意系统默认的离线语法名称为defaut, 它不可以通过API来添加，删除以及修改

    Args:
        grammar：需要删除配置的语法名称。

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    msg: string提示信息
                }

    """
    voice_url = basic_url+"voice/asr/offlinesyntax"
    param = {"grammar": grammar}
    json_data = json.dumps(param)
    response = requests.delete(url=voice_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_voice_asr_offline_syntax(grammar: str):
    """获取指定语法名称下的所有配置

    Args:
        grammar(str):需要获得配置结构的语法名称。

    Returns:
           Dict:
           e.g::
 
                {
                    grammar:string定义语法名称,请输入纯字母
                    slot:
                        [
                            声明槽,内容为字母不数字。所有的操作符及关键词均为半角字符,不支持全角字符。
                                                        {
                                name:string槽名称
                            }
                        ]
                    start:string     定义开始规则,内容为字母不数字。所有的操作符及关键词均为半角字符,不支持全角字符。
                    startinfo:string 定义开始规则详细内容
                    rule:
                        [
                            所有的离线语法规则
                                                        {
                                name: string表示规则名称
                                value:string表示规则内容
                            }
                        ]
                }
    Examples:
        >>>  res = YanAPI.get_voice_asr_offline_syntax('LocalCmd')
             print(res)
            =======================================================
            {
                "grammar": "LocalCmd",
                "rule": [
                            {
                                "name": "ok",
                                "value": "我想|我要|请|帮我|我想要|请帮我"
                            }
                        ],
                "slot": [
                            {
                                "name": "ok"
                            }
                        ],
                "start": "LocalCmdStart",
                "startinfo": "<ok>"
            }

    """
    voice_url = basic_url+"voice/asr/offlinesyntax"
    params = {"body": grammar}
    response = requests.get(url=voice_url, headers=headers, params=params)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def create_voice_asr_offline_syntax(object: Dict):
    """创建一个新的离线语法名称配置

    Args:
        object:按照语法规则填写的一个dict。

    Examples:
        e.g:
    >>> {
            "grammar": "LocalCmd",
            "rule": [
                        {
                            "name": "ok",
                            "value": "我想|我要|请|帮我|我想要|请帮我"
                        }，
                        {
                            "name": "hello",
                            "value": "在不在|欢迎"
                        }
                    ],
            "slot": [
                        {
                            "name": "ok"
                        }，
                        {
                            "name": "hello"
                        }
                    ],
            "start": "LocalCmdStart",
            "startinfo": "<ok>|<hello>"
        }

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    voice_url = basic_url+"voice/asr/offlinesyntax"
    param = object
    json_data = json.dumps(param)
    response = requests.post(url=voice_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def update_voice_asr_offline_syntax(object: Dict):
    """修改已有语法配置中的命令词和对应的返回值

    可用于配置或添加新的离线命令词

    Args:
        object(Dict):按照语法规则填写的一个dict。（其中的语法名称是需要修改内容的已有名称）

    Examples:

        >>>  {
                "grammar": "LocalCmd",
                "rule": [
                            {
                                "name": "ok",
                                "value": "我想|我要|请|帮我|我想要|请帮我"
                            }，
                            {
                                "name": "hello",
                                "value": "在不在|欢迎"
                            }
                        ],
                "slot": [
                            {
                                "name": "ok"
                            }，
                            {
                                "name": "hello"
                            }
                        ],
                "start": "LocalCmdStart",
                "startinfo": "<ok>|<hello>"
            }

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    voice_url = basic_url+"voice/asr/offlinesyntax"
    param = object
    json_data = json.dumps(param)
    response = requests.put(url=voice_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_voice_asr_offline_syntax_grammars():
    """获取所有离线语法名称

    Note:
        请注意系统默认的离线语法名称为defaut, 它不可以通过API来添加，删除以及修改。

    Returns:
           Dict:
           e.g::
 
                {
                    "grammar": [
                                    {
                                        "name": "default"
                                    },
                                    {
                                        "name": String 离线语法名称
                                    }
                                ]
                }

    """
    voice_url = basic_url+"voice/asr/offlinesyntax/grammars"
    response = requests.get(url=voice_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def stop_voice_iat():
    """停止语音听写

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    msg: string提示信息
                }

    """
    voice_url = basic_url+"voice/iat"
    response = requests.delete(url=voice_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_voice_iat():
    """获取语音听写结果

    Returns:
           Dict:
           e.g::
 
               {
                    code: integer (int32)返回码，0表示正常
                    status: string  idle 非执行状态 run 正在运行
                    timestamp: integer (int32)时间戳, Unix标准时间
                    data:
                                                {
                            语音听写返回数据
                        }
                    msg:string提示信息
                }

    """
    res=get_robot_language()
    voice_url = basic_url+"voice/iat"
    response = requests.get(url=voice_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    ret=get_robot_language()
    if ret["data"]["language"]=="ko" :
        return res
    res["data"] = json.loads(res["data"].strip(
        b'\x00'.decode()))
    return res


def start_voice_iat(timestamp: int = 0):
    """开始语音听写

    当语音听写处于工作状态时，需要先停止当前的语音听写。

    Args:
        timestamp: integer (int32)时间戳, Unix标准时间

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    voice_url = basic_url+"voice/iat"
    param = {"timestamp": timestamp}
    json_data = json.dumps(param)
    response = requests.put(url=voice_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def sync_do_voice_iat_value():
    """执行一次语音听写并获得返回结果(便利方法)。

    Returns:
        str:识别到的内容

    """
    timestamp = int(time.time())
    successed = start_voice_iat(timestamp=timestamp)
    if not successed:
        return ""
    coroutine = __wait_result(timestamp,get_voice_iat)
    loop = asyncio.get_event_loop()
    task = loop.create_task(coroutine)
    loop.run_until_complete(task)
    res = task.result()
    if not __resIsSuccess(res):
        logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
        return ""
    ret=get_robot_language()
    if ret["data"]["language"]=="ko" :
        return res["data"].strip()
    words = res["data"].get("text")
    if not words:
        return ""
    words = words.get("ws")
    if not words:
        return ""
    result = ""
    for word in words:
        result += word['cw'][0]['w']
    result = result.strip().lower()
    return result

def sync_do_voice_iat():
    """执行一次语音听写并获得返回结果。

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    status:string idle 非执行状态run 正在运行
                    timestamp:integer (int32)时间戳, Unix标准时间
                    data:
                       {
                            语义理解返回内容数据
                        }
                    msg: string提示信息
            }

    """
    timestamp = int(time.time())
    res = start_voice_iat(timestamp=timestamp)
    if res['code'] != 0:
        logging.error("do voice iat failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return res
    coroutine = __wait_result(timestamp, get_voice_iat)
    loop = asyncio.get_event_loop()
    task = loop.create_task(coroutine)
    loop.run_until_complete(task)
    #result = asyncio.run(coroutine)
    return task.result()


def stop_voice_tts():
    """停止语音播报任务

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    voice_url = basic_url+"voice/tts"
    response = requests.delete(url=voice_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_voice_tts_state(timestamp: int = None):
    """获取指定或者当前工作状态

    带时间戳为指定任务工作状态，如果无时间戳则当前任务。

    Args:
        timestamp(int64):时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    status: string
                    当任务为语语音合成的时候，状态如下：idle 任务不存在,run 播放该段语音,build 正在合成该段语音,wait 处于等待执行状态
                    timestamp: integer (int32)时间戳, Unix标准时间
                    data:
                         {
                            语音返回数据
                        }
                    msg:string提示信息
                }

    """
    voice_url = basic_url+"voice/tts"
    response = requests.get(url=voice_url, headers=headers)
    if timestamp != None:
        params = {'timestamp': timestamp}
        response = requests.get(url=voice_url, headers=headers, params=params)
    res = json.loads(str(response.content.decode("utf-8")))
    res["data"] = json.loads(
        str(res["data"].strip(b'\x00'.decode())))
    return res


def start_voice_tts(tts: str = "", interrupt: bool = True, timestamp: int = 0):
    """开始语音合成任务

    合成指定的语句并播放。当语音合成处于工作状态时可以接受新的语音合成任务.

    Args:
        tts(str):待合成的文字
        interrupt(bool):是否可以被打断，默认为True
        timestamp(int):时间戳, Unix标准时间

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    voice_url = basic_url+"voice/tts"
    param = {"tts": tts, "interrupt": interrupt, "timestamp": timestamp}
    json_data = json.dumps(param)
    response = requests.put(url=voice_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def sync_do_tts(tts: str = "", interrupt: bool = True):
    """执行语音合成任务,合成完成后返回

    Args:
        tts(str):待合成的文字
        interrupt(bool):是否可以被打断，默认为True

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer (int32)返回码，0表示正常
                    status: string
                    当任务为语语音合成的时候，状态如下：idle 任务不存在,run 播放该段语音,build 正在合成该段语音,wait 处于等待执行状态
                    timestamp: integer (int32)时间戳, Unix标准时间
                    data:
                        {
                            语音返回数据
                        }
                    msg:string提示信息
                }

    """
    t = int(time.time())
    res = start_voice_tts(tts = tts, interrupt = interrupt, timestamp = t)
    if res['code'] != 0:
        logging.error("do tts failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return res
    coroutine = __wait_result_common(timestamp=t, getFuc=get_voice_tts_state, args=(t,))
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    # Success Example
    return tasks.result()

####Visions####


def get_visual_task_result(option: str, type: str):
    """获取视觉任务结果

    Args:
        option(str):模型名 face、object、color、hand
        type(str):任务名称 face(age,gender,age_group,quantity,expression,recognition,tracking,mask,glass) object(recognition)  color(color_detect)  hand(gesture)

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer返回码，0表示正常
                    type:string消息类型。 一次只返回一种类型的数据。 type 允许的值为:recognition,tracking,gender,age_group,quantity,color_detect,age,expression
                    data:
                                                {
                            analysis: {
                                        age: integer
                                        group: string
                                        gender: string
                                        expression: string
                                    }
                            recognition:
                                                                                {
                                            name:string
                                        }
                            quantity: integer数量 (整数)
                            color:
                                [
                                    所有可返回的颜色列表, 列表值有none、black、gray、white、red、orange、yellow、green、cyan、blue、purple
                                                                        {
                                        name:string
                                    }
                                ]
                        }
                    timestamp:integer (int64)任务时间戳
                    status: string状态
                    msg: string提示信息
                }

    """
    visions_url = basic_url+"visions"
    params = {'option': option, 'type': type}
    response = requests.get(url=visions_url, headers=headers, params=params)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def __control_visual_task(option: str, type: str, operation: str = "start", timestamp: int = 0):
    """指定视觉任务停止或开始

    Args:
        option(str):任务名称 option 允许上传的值有(face,color,object)
        type(str):任务类型。 type 允许上传的值有tracking、recognition、gender、age_group、quantity、color_detect、age、expression、mask(口罩)、glass(眼镜)
        operation(str):执行命令。 operation 允许上传的值有start、stop
        timestamp(int):任务时间戳

    Note:
        组合限制 当option的值为face的时候，type的值如下：

        tracking

        recognition

        quantity

        age_group

        gender

        age

        expression

        当option的值为color的时候，type的值如下：

        color_detect

        当option的值为object的时候，type的值如下：

        recognition

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    visions_url = basic_url+"visions"
    param = {"option": option, "type": type,
             "operation": operation, "timestamp": timestamp}
    json_data = json.dumps(param)
    response = requests.put(url=visions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def start_face_recognition(type: str, timestamp: int = 0):
    """开始人脸识别

    Args:
        type(str):任务类型。可选值 tracking | recognition | quantity | age_group | gender | age | expression | mask(口罩) | glass(眼镜)
        timestamp(int):任务时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }
    """
    return __control_visual_task(option = 'face',type=type,operation='start',timestamp = timestamp)

def stop_face_recognition(type: str, timestamp: int = 0):
    """停止人脸识别

    Args:
        type(str):任务类型。可选值 tracking | recognition | quantity | age_group | gender | age | expression | mask(口罩) | glass(眼镜)
        timestamp(int):任务时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }
    """
    return __control_visual_task(option = 'face',type=type,operation='stop',timestamp = timestamp)

def sync_do_face_recognition_value(type:str):
    """执行人脸识别,识别完成后返回

    Args:
        type(str):任务类型。可选值 recognition | quantity | age_group | gender | age | expression | mask(口罩) | glass(眼镜)

    Note:
        type 为recognition时返回name:str(需提前调用set_vision_tag对图片进行标记)

        type 为quantity时返回人脸数量:int

        type 为age_group时返回年龄段:str (baby、children、juvenile、youth、middle_age、old_age、none)

        type 为gender时返回性别:str (male、female、none)

        type 为age时返回年龄:int

        type 为expression时返回表情:str (anger、disgust、fear、happy、sad、surprised、normal、none)

        type 为mask时返回口罩佩戴情况:str (masked、unmasked、not masked well)

        type 为glass时返回眼镜佩戴情况:str (grayglass、normalglass、noglass)

    """
    timestamp = int(time.time())
    startSuccess = start_face_recognition(type,timestamp)
    if not startSuccess:
        return None
    coroutine = __wait_result_common(timestamp=timestamp, getFuc=get_visual_task_result, args=("face", type))
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    ret = tasks.result()
    if not __resIsSuccess(ret):
        logging.error("error code = %d msg = %s",ret.get("code",-1),ret.get("msg",""))
        return None
    faceRes = RobotVisualTaskResult(ret["data"])
    ret = getattr(faceRes,type)
    return ret

def sync_do_face_recognition(type: str):
    """执行人脸识别,识别完成后返回

    Args:
        type(str):任务类型。可选值 recognition | quantity | age_group | gender | age | expression | mask(口罩) | glass(眼镜)

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer返回码，0表示正常
                    type:string消息类型。 一次只返回一种类型的数据。 type 允许的值为:recognition,tracking,gender,age_group,quantity,color_detect,age,expression,mask,glass
                    data:
                          {
                            analysis: {
                                        age: integer
                                        group: string
                                        gender: string
                                        expression: string
                                        mask: string  口罩识别结果(masked、unmasked、not masked well)
                                        glass: string 眼镜识别结果(grayglass、normalglass、noglass)
                                    }
                            quantity: integer数量 (整数)
                        }
                    timestamp:integer (int64)任务时间戳
                    status: string状态
                    msg: string提示信息
                }
    """
    timestamp = int(time.time())
    res = start_face_recognition(type,timestamp)
    if res['code'] != 0:
        logging.error("do face recognition failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return res
    coroutine = __wait_result_common(timestamp=timestamp, getFuc=get_visual_task_result, args=("face", type))
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return tasks.result()

def start_gesture_recognition(timestamp: int = 0):
    """开始手势识别

    Args:
        timestamp(int):任务时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }
    """
    return __control_visual_task(option = 'hand',type='gesture',operation='start',timestamp = timestamp)

def stop_gesture_recognition(timestamp: int = 0):
    """停止手势识别

    Args:
        timestamp(int):任务时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }
    """
    return __control_visual_task(option = 'hand',type='gesture',operation='stop',timestamp = timestamp)

def sync_do_gesture_recognition():
    """执行手势识别,识别完成后返回

    Note:
        可能的返回值及含义如下。
        ok	    OK
        good	点赞
        bad	    差评
        victory	胜利
        heart_hand_sign	比心
        digital_6_gesture   比6
        none    (未识别到)

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {
                                "gesture": "ok"
                            },
                    "msg": "Success",
                    "status": "idle",
                    "timestamp": 1551838515,
                    "type": "gesture"
                }
    """
    timestamp = int(time.time())
    res = start_gesture_recognition(timestamp)
    if res['code'] != 0:
        logging.error("do gesture recognition failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return res
    coroutine = __wait_result_common(timestamp=timestamp, getFuc=get_visual_task_result, args=("hand",'gesture'))
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return tasks.result()

def start_color_recognition(timestamp: int = 0):
    """开始颜色识别

    Args:
        timestamp(int):任务时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }
    """
    return __control_visual_task(option = 'color',type="color_detect",operation='start',timestamp = timestamp)

def stop_color_recognition(timestamp: int = 0):
    """停止颜色识别

    Args:
        timestamp(int):任务时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }
    """
    return __control_visual_task(option = 'color',type="color_detect",operation='stop',timestamp = timestamp)

def sync_do_color_recognition():
    """执行颜色识别,识别完成后返回

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer返回码，0表示正常
                    type:string消息类型。 一次只返回一种类型的数据。 type 允许的值为:recognition,tracking,gender,age_group,quantity,color_detect,age,expression
                    data:
                          {
                            color:
                                [
                                    所有可返回的颜色列表, 列表值有none、black、gray、white、red、orange、yellow、green、cyan、blue、purple
                                    {
                                        name:string
                                    }
                                ]
                        }
                    timestamp:integer (int64)任务时间戳
                    status: string状态
                    msg: string提示信息
                }
    """
    timestamp = int(time.time())
    res = start_color_recognition(timestamp)
    if res['code'] != 0:
        logging.error("do color recognition failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return res
    coroutine = __wait_result_common(timestamp=timestamp, getFuc=get_visual_task_result, args=("color", "color_detect"))
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return tasks.result()

def start_object_recognition(timestamp: int = 0):
    """开始物体识别

    Args:
        timestamp(int):任务时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }
    """
    return __control_visual_task(option = 'object',type="recognition",operation='start',timestamp = timestamp)

def stop_object_recognition(timestamp: int = 0):
    """停止物体识别

    Args:
        timestamp(int):任务时间戳

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }
    """
    return __control_visual_task(option = 'object',type="recognition",operation='stop',timestamp = timestamp)

def sync_do_object_recognition():
    """执行物体识别,识别完成后返回

    Returns:
           Dict:
           e.g::
 
                {
                    code: integer返回码，0表示正常
                    type:string消息类型。 一次只返回一种类型的数据。 type 允许的值为:recognition,tracking,gender,age_group,quantity,color_detect,age,expression
                    data:
                          {
                            recognition:
                                        {
                                            name:string
                                        }
                            quantity: integer数量 (整数)
                        }
                    timestamp:integer (int64)任务时间戳
                    status: string状态
                    msg: string提示信息
                }
    """
    timestamp = int(time.time())
    res = start_object_recognition(timestamp)
    if res['code'] != 0:
        logging.error("do object recognition failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return res
    coroutine = __wait_result_common(timestamp=timestamp, getFuc=get_visual_task_result, args=("object", "recognition"))
    loop = asyncio.get_event_loop()
    tasks = loop.create_task(coroutine)
    loop.run_until_complete(tasks)
    return tasks.result()

def do_face_entry(name:str):
    """进行人脸录入

    Args:
        name(str):录入的人名

    Returns:
        bool: (True 执行成功  False 执行失败)

    """
    res = take_vision_photo()
    if not __resIsSuccess(res):
        logging.error("do face entry failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return False
    path = os.path.expanduser("~")+"/"
    get_vision_photo(res["data"]["name"], path)
    photo = path + res["data"]["name"]
    photo_name = res["data"]["name"]
    #上传人脸样本到数据库
    res = upload_vision_photo_sample(photo)
    if os.path.exists(photo) and os.path.isfile(photo):  # 如果文件存在
        os.remove(photo)
    if not __resIsSuccess(res):
        logging.error("do face entry failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return False
    #为图片数据打tag
    res = set_vision_tag([photo_name],name)
    if not __resIsSuccess(res):
        logging.error("do face entry failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
        return False
    return True


def delete_vision_photo(name: str):
    """删除指定名称的图片

    用户拍照默认存储目录：/tmp/photo

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    visions_url = basic_url+"visions/photos"
    param = {"name": name}
    json_data = json.dumps(param)
    response = requests.delete(
        url=visions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_vision_photo(name: str, savePath: str = "./"):
    """获取指定名称的照片，并保存到特定路径下面

    Args:
        name(str):照片名
        path(str):照片本地存储路径。

    Returns:
           data:图片内容

    """
    visions_url = basic_url+"visions/photos"
    params = {'body': name}
    response = requests.get(url=visions_url, headers=headers, params=params)
    res = response.content
    with open(savePath+name, "wb") as fp:
        fp.write(res)
    return res


def take_vision_photo(resolution: str = "640x480"):
    """拍一张照片

    默认存储路径为/tmp/photo

    Args:
        resolution(str):照片分辨率。 默认拍照分辨率为”640x480”，最大拍照分辨率为1920x1080

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {name:string 拍摄的照片名},
                    "msg": "Success"
                }

    """
    visions_url = basic_url+"visions/photos"
    param = {"resolution": resolution}
    json_data = json.dumps(param)
    response = requests.post(url=visions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_vision_photo_list():
    """获取机器人照片列表

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": [
                                {
                                    "name": "name"
                                },
                                {
                                     "name": "name"
                                 }
                            ],
                    msg: string提示信息
                }

    """
    visions_url = basic_url+"visions/photos/list"
    response = requests.get(url=visions_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def delete_vision_photo_sample(name: str):
    """删除指定名称的样本照片

    Args:
        name(str):需要删除的样本照片名称

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    visions_url = basic_url+"visions/photosamples"
    param = {"name": name}
    json_data = json.dumps(param)
    response = requests.delete(
        url=visions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_vision_photo_samples():
    """获取样本照片列表

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": [
                                {
                                    "name": "name"
                                },
                                {
                                     "name": "name"
                                 }
                            ],
                    msg: string提示信息
                }

    """
    visions_url = basic_url+"visions/photosamples"
    response = requests.get(url=visions_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def upload_vision_photo_sample(filePath: str):
    """上传样本图片到特定文件夹

    默认Sample文件夹

    Parameters
    ----------
    filePath:str
        需要上传的文件路径

    Returns
    -------
    Dict
        e.g::

            {
                "code": 0,
                "data": {},
                "msg": "Success"
            }

    """
    visions_url = basic_url+"visions/photosamples"
    headers = {'Authorization': 'multipart/form-data'}
    files = {'file': open(filePath, 'rb')}
    response = requests.post(url=visions_url, files=files, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def open_vision_stream(resolution: str = "640x480"):
    """打开摄像头网络视频流

    用户可以通过浏览器直接接收视频．视频将以mjpg格式通过http的形式发布url: http://机器人ip地址:8000。

    Args:
        resolution(str):视频分辨率。默认视频分辨率为640x480

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    visions_url = basic_url+"visions/streams"
    param = {"resolution": resolution}
    json_data = json.dumps(param)
    response = requests.post(url=visions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def close_vision_stream():
    """关闭摄像头网络视频流

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    visions_url = basic_url+"visions/streams"
    response = requests.delete(url=visions_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def delete_vision_tag(tag: str,mode:str = "all"):
    """删除指定标签

    Args:
        tag(str):标签名称。
        mode(str):选择只移除标签或者标签下的所有样本(包括标签), tags为只移除标签样本保留, all为样本和标签都删除, 默认删除标签下的所有样本(包括标签)
    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    visions_url = basic_url+"visions/tags"
    param = {"tags": tag,"mode":mode}
    json_data = json.dumps(param)
    response = requests.delete(
        url=visions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def get_vision_tags():
    """获取样本标签列表

    Returns:
           Dict:
           e.g::
 
                {
                    code (integer): Return code, 0 means success ,
                    data (list[VisionsPutTags], optional),
                    msg (string): Return message
                }
                VisionsPutTags {
                                    resources (list[string]): Vision sample's name list. ,
                                    tags (string): Vision tags.
                                }
    Examples:
           >>>  res = YanAPI.get_vision_tags()
                print(res)
                ===================
                {
                    "code": 0,
                    "data": [
                                {
                                    "resources": [
                                             "img_20191205_114745_7283.jpg",
                                             "img_20191206_023443_7058.jpg"
                                            ],
                                    "tags": "ok"
                                },
                                {
                                    "resources": [
                                                "img_20191205_114745_7283.jpg"
                                                ],
                                    "tags": "lee"
                                }
                            ],
                     "msg": "Success"
                 }
    """
    visions_url = basic_url+"visions/tags"
    response = requests.get(url=visions_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def set_vision_tag(resources: List[str], tag: str):
    """给已有样本图片打标签

    打标签前请先上传样本图片

    Args:
        resources(List[str]):需要打标签的样本图片名称列表。
        tag(str):标签名称

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    visions_url = basic_url+"visions/tags"
    param = {"resources": resources, "tags": tag}
    json_data = json.dumps(param)
    response = requests.put(url=visions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res

def do_visions_visible(operation, task):
    """
	开启或关闭视觉任务视频流
	参数
	operation (str) - 操作类型：start-开启，stop-关闭
	task (str) - 视频流任务类型：face_recognition_remote-人脸识别/ face_attribute_remote-人脸分析/ color_detect_remote-颜色检测/ object_recognition_remote-物体识别/ gesture_recognition_remote-手势识别/apriltag_recognition_remote-apriltag识别
	返回类型 dict
	返回说明
	{
    	code:integer 返回码：0表示正常
    	data:
        {
            url:str 视频流的URL地址
        }
    	msg:string 提示信息
	}
    """
    vision_visible_url = basic_url + "visions_visible"
    param = {"operation":operation, "type":task}
    json_data = json.dumps(param)
    response = requests.put(url = vision_visible_url, data = json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    print(res)
    if operation == 'start':
        try:
            print("url --> %s"%res['data']['url'])
        except:
            pass
    return res

def show_visions_result(operation):
    """
	显示视觉任务视频流
	参数
	task (str) - 视频流任务类型：face_recognition_remote-人脸识别/ face_attribute_remote-人脸分析/ color_detect_remote-颜色检测/ object_recognition_remote-物体识别/ gesture_recognition_remote-手势识别/ apriltag_recognition_remote-apriltag识别
	返回类型 stream(mjpeg)
	返回说明 视频流弹窗
    """
    global ip
    counter = 0
    #camera_list = [None, None, None]
    try:
        res = do_visions_visible('start',operation)
        if res['code'] == 20003:
            if ip != "127.0.0.1":
                port = res['data']['url'][7:][res['data']['url'][7:].find(':') + 1:]
                print("port --> %s"%port)
                url = "http://" + ip + ":" + port
                print("new url:%s"%url)
            else:
                url = res['data']['url']
        elif res['code'] == 0:
            url = res['data']['url']
        else:
            print(res['msg'])
            return
    except:
        print("Something wrong I can not open...")
        cv2.destroyAllWindows()
        cv2.waitKey(1)
        do_visions_visible('stop',operation)
        return
    camera = cv2.VideoCapture(url)
    ret = camera.isOpened()
    if not ret:
        print('Unable to accquire data')
        cv2.destroyAllWindows()
        cv2.waitKey(1)
        do_visions_visible('stop',operation)
        return
    try:
        while ret:
            ret, frame = camera.read()
            if not ret:
                print('fail to read data, ip may not correct')
                if counter > 2:
                    print('maximum 2 retry occur, exit...')
                    cv2.destroyAllWindows()
                    cv2.waitKey(1)
                    camera.release()
                    exit(0)
                else:
                    cv2.destroyAllWindows()
                    cv2.waitKey(1)
                    camera.release()
                    counter += 1
                    try:
                        res = do_visions_visible('start',operation)
                        url = res['data']['url']
                        ret = 1 # give it a try...
                    except:
                         exit(0)
                    print('new url --> %s' % url)
                    camera = cv2.VideoCapture(url)
                    continue
            else:
                counter = 0
            cv2.imshow(operation, frame)
            cv2.waitKey(1)
            if cv2.getWindowProperty(operation, cv2.WND_PROP_VISIBLE) <= 0:
                break
        cv2.destroyAllWindows()
        cv2.waitKey(1)
        do_visions_visible('stop',operation)
    except:
        print('program crash')
        cv2.destroyAllWindows()
        cv2.waitKey(1)
        do_visions_visible('stop',operation)
####Subscriptions####


def stop_subscribe_motion(url: str):
    """停止运动控制状态信息订阅

    Args:
        url(str):需要停止的订阅的接收地址

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/motions"
    param = {"url": url}
    json_data = json.dumps(param)
    response = requests.delete(
        url=subscriptions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def start_subscribe_motion(url: str, timeout: int = 10):
    """订阅运动控制状态信息

    Args:
        url(str):订阅的接收地址
        timeout(int):订阅超时的时间，单位：秒Default: 10  minimum:1 maximum:60

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/motions"
    param = {"url": url, "timeout": timeout}
    json_data = json.dumps(param)
    response = requests.post(url=subscriptions_url,
                             data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def stop_subscribe_motion_gait(url: str):
    """停止步态运动控制状态信息订阅

    Args:
        url(str):需要停止的订阅的接收地址

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/motions/gait"
    param = {"url": url}
    json_data = json.dumps(param)
    response = requests.delete(
        url=subscriptions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def start_subscribe_motion_gait(url: str, timeout: int = 10):
    """订阅步态运动控制状态信息

    Args:
        url(str):订阅的接收地址
        timeout(int):订阅超时的时间，单位：秒Default: 10  minimum:1 maximum:60

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/motions/gait"
    param = {"url": url, "timeout": timeout}
    json_data = json.dumps(param)
    response = requests.post(url=subscriptions_url,
                             data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def stop_subscribe_sensor(url, type, id=0, slot=0):
    """停止传感器订阅服务。

    Args:
        url(str):订阅的接收地址
        type(str):传感器类型，可选取值（gyro, ultrasonic, environment, infrared, touch, pressure）
        id(int):minimum:1 maximum:126传感器地址，可不填
        slot(int):maximum:6传感器槽位号

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/sensors"
    param = {"url": url, "type": type}
    if (id != 0):
        param["id"] = id
    if (slot != 0):
        param["slot"] = slot
    json_data = json.dumps(param)
    response = requests.delete(
        url=subscriptions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def start_subscribe_sensor(url: str, type: str, id=0, slot=0, timeval=100, timeout=10):
    """订阅传感器消息

    Args:
        url(str):订阅的接收地址
        type(str): 传感器类型，取值如下：gyro, ultrasonic, environment, infrared, touch, pressure
        id(int):传感器地址，可不填 minimum:1 maximum:126
        slot(int):传感器槽位号 maximum:6
        timeval(int):上报的最短时间间隔，单位ms minimum:100 maximum:5000
        timeout(int):订阅的最大重试时间，单位：秒 Default 10 minimum:1 maximum:60

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/sensors"
    param = {"url": url, "type": type, "timeval": timeval, "timeout": timeout}
    if (id != 0):
        param["id"] = id
    if (slot != 0):
        param["slot"] = slot
    json_data = json.dumps(param)
    response = requests.post(url=subscriptions_url,
                             data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def stop_subscribe_vision(url: str, type: str):
    """停止指定视觉任务订阅

    Args:
        type(str):消息类型。type 允许上传的值有 face_recognition, object_recognition, gender, age, age_group, face_quantity, expression, color_detect
        url(str):订阅的接收地址

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/visions"
    param = {"url": url, "type": type}
    json_data = json.dumps(param)
    response = requests.delete(
        url=subscriptions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def start_subscribe_vision(url: str, type: str, timeout:int=10):
    """订阅指定视觉任务消息

    Args:
        type(str):消息类型。type 允许上传的值有
        face_recognition, object_recognition, gender, age, age_group, face_quantity, expression, color_detect
        url(str): 订阅的接收地址
        timeout(int):minimum:1 maximum:60订阅的最大重试时间，单位：秒 Default 10

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/visions"
    param = {"url": url, "type": type, "timeout": timeout}
    json_data = json.dumps(param)
    response = requests.post(url=subscriptions_url,
                             data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def stop_subscribe_voice_asr(url: str):
    """停止订阅语义理解消息

    Args:
        url(str):需要停止的订阅的接收地址

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/voice/asr"
    param = {"url": url}
    json_data = json.dumps(param)
    response = requests.delete(
        url=subscriptions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def start_subscribe_voice_asr(url: str, timeout: int = 10):
    """订阅语义理解消息

    Args:
        url(str):需要停止的订阅的接收地址
        timeout(int):订阅超时的时间，单位：秒Default: 10 minimum:1 maximum:60

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/voice/asr"
    param = {"url": url, "timeout": timeout}
    json_data = json.dumps(param)
    response = requests.post(url=subscriptions_url,
                             data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def stop_subscribe_voice_iat(url: str):
    """停止订阅语音听写消息

    Args:
        url(str):需要停止的订阅的接收地址

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/voice/iat"
    param = {"url": url}
    json_data = json.dumps(param)
    response = requests.delete(
        url=subscriptions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def start_subscribe_voice_iat(url: str, timeout=10):
    """订阅语音听写消息

    Args:
        url(str):需要停止的订阅的接收地址
        timeout(int):订阅超时的时间，单位：秒Default: 10 minimum:1 maximum:60

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/voice/iat"
    param = {"url": url, "timeout": timeout}
    json_data = json.dumps(param)
    response = requests.post(url=subscriptions_url,
                             data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def stop_subscribe_voice_tts(url: str):
    """停止订阅语音播报消息

    Args:
        url(str):需要停止的订阅的接收地址

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/voice/tts"
    param = {"url": url}
    json_data = json.dumps(param)
    response = requests.delete(
        url=subscriptions_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def start_subscribe_voice_tts(url: str, timeout: int = 10):
    """订阅语音播报消息

    Returns:
           Dict:
           e.g::
 
                {
                    "code": 0,
                    "data": {},
                    "msg": "Success"
                }

    """
    subscriptions_url = basic_url+"subscriptions/voice/tts"
    param = {"url": url, "timeout": timeout}
    json_data = json.dumps(param)
    response = requests.post(url=subscriptions_url,
                             data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


@unique
class GamepadKey(Enum):
    """蓝牙手柄按键名

    :meta private:
    """
    L1 = "key_L1"
    L2 = "key_L2"
    R1 = "key_R1"
    R2 = "key_R2"
    A = "key_A"
    B = "key_B"
    X = "key_X"
    Y = "key_Y"
    DPAD_UP = "key_DPAD_UP"
    DPAD_DOWN = "key_DPAD_DOWN"
    DPAD_LEFT = "key_DPAD_LEFT"
    DPAD_RIGHT = "key_DPAD_RIGHT"
    DPAD_UP_LEFT = "key_DPAD_UP_LEFT"
    DPAD_UP_RIGHT = "key_DPAD_UP_RIGHT"
    DPAD_DOWN_LEFT = "key_DPAD_DOWN_LEFT"
    DPAD_DOWN_RIGHT = "key_DPAD_DOWN_RIGHT"
    L_STICK = "key_L_STICK"
    R_STICK = "key_R_STICK"
    L_STICK_UP = "key_L_STICK_UP"
    L_STICK_DOWN = "key_L_STICK_DOWN"
    L_STICK_LEFT = "key_L_STICK_LEFT"
    L_STICK_RIGHT = "key_L_STICK_RIGHT"
    R_STICK_UP = "key_R_STICK_UP"
    R_STICK_DOWN = "key_R_STICK_DOWN"
    R_STICK_LEFT = "key_R_STICK_LEFT"
    R_STICK_RIGHT = "key_R_STICK_RIGHT"
    BT = "key_BT"
    START = "key_START"
    POWER = "key_POWER"
    RESERVE = "key_RESERVE"

class GamepadKeymap():
    """手柄按键和动作文件映射

    :meta private:

    Note:
    {
      "htsName": "Stop2",
      "keyName": "key_L2",
      "longPress": false
    }
    """
    def __init__(self, keyName: GamepadKey=None, htsName=None, longPress=False):
        self.keyName = keyName.value
        self.htsName = htsName
        self.longPress = longPress

    @property
    def key_name(self):
        return self.keyName

    @property
    def hts_name(self):
        return self.htsName

    @property
    def long_press(self):
        return self.longPress


def get_gamepad_keymap():
    """获取蓝牙手柄按键和动作文件映射关系
    Args:

    Returns:
           Dict:
           e.g::
 
               {
                  "code": 0,
                  "data": [
                    {
                      "htsName": "OneStepTurnLeft",
                      "keyName": "key_L1",
                      "longPress": true
                    },
                    ...
                  ],
                  "msg": "Success"
                }
    """
    voice_url = basic_url+"gamepad/keymap/get"
    response = requests.get(url=voice_url, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def set_gamepad_keymap(key_name: GamepadKey, hts_name: str, long_press: bool = False):
    """设置单个按键的动作

      Args:
          key_name(GamepadKey): 按键名称
          hts_name(str): 动作文件名
          long_press(bool): 是否需要长按
      Returns:
             json: 
             e.g::
                  {
                      code: integer (int32)返回码，0表示正常
                      msg: string提示信息
                   }

    """
    return set_gamepad_keymaps([GamepadKeymap(key_name, hts_name, long_press)])


def set_gamepad_keymaps(keymaps: List[GamepadKeymap]):
    """设置多个按键的动作

      Args:
          keymaps(List[GamepadKeymap]): 按键映射对象列表

      Returns:
             json: 
             e.g::
                  {
                      code: integer (int32)返回码，0表示正常
                      msg: string提示信息
                   }

    """
    servos_url = basic_url + "gamepad/keymap/set"

    param = {
        "keymaps": keymaps
    }

    def default(obj):
        if isinstance(obj, GamepadKeymap):
            return obj.__dict__
        return obj

    json_data = json.dumps(param, default=default)
    # print(json_data)
    response = requests.put(url=servos_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


def reset_gamepad_keymap(key_name: GamepadKey):
    """重置单个按键到默认配置

      Args:
          key_name(GamepadKey): 按键名

      Returns:
             json: 
             e.g::
                  {
                      code: integer (int32)返回码，0表示正常
                      msg: string提示信息
                   }

    """
    return reset_gamepad_keymaps([key_name])


def reset_gamepad_keymaps(key_name_list: List[GamepadKey] = None, reset_all: bool = False):
    """重置多个按键或者全部按键到默认配置

      Args:
          key_name_list(List[GamepadKey]): 按键名列表
          reset_all(bool): 是否重置全部按键，默认False
      Returns:
             json: 
             e.g::
                  {
                      code: integer (int32)返回码，0表示正常
                      msg: string提示信息
                   }

    """
    servos_url = basic_url + "gamepad/keymap/reset"

    if key_name_list is None:
        key_name_list = []

    param = {
        "keynames": key_name_list,
        "resetall": reset_all
    }

    def default(obj):
        if isinstance(obj, GamepadKey):
            return obj.value
        return obj

    json_data = json.dumps(param, default=default)
    # print(json_data)
    response = requests.put(url=servos_url, data=json_data, headers=headers)
    res = json.loads(str(response.content.decode("utf-8")))
    return res


async def __wait_result(timestamp, getFuc):
    while True:
        res = getFuc()
        if(timestamp == res["timestamp"]):
            status = res["status"]
            if status == "idle":
                return res
            else:
                await asyncio.sleep(1)

async def __wait_result_QR(getFuc,timeOut,checkStream = False):
    timeCount = 0
    beginTime = time.time()
    global PqrStream
    while True:
        res = getFuc()
        if checkStream and not (PqrStream is None) and not PqrStream.is_alive():
            stop_QR_code_recognition()
            return res
        nowTime = time.time()
        if timeOut > 0 and int(nowTime - beginTime)>timeOut:
            stop_QR_code_recognition()
            return res
        if("idle" == res["status"]) or len(res["data"]["contents"]) !=0:
            if not (PqrStream is None):
                PqrStream.terminate()
                PqrStream = None
            return res
        else:
            timeCount += 1
            await asyncio.sleep(1)

async def __wait_result_common(timestamp, getFuc, args=()):
    while True:
        res = getFuc(*args)
        # print(res)
        if(timestamp == res["timestamp"]):
            status = res["status"]
            if status == "idle":
                return res
            else:
                await asyncio.sleep(1)

# async def __wait_result_common2(name, start_time, getFuc):
#     while True:
#         res = getFuc()
#         print(res)
#         if res['data']['name'] == "":
#             return res
#         if res['data']['status'] == 'run' and res['data']['name'] == name:
#             if start_time is None: # music
#                 await asyncio.sleep(1)
#             else:
#                 if start_time == res['data']['timestamp']: # motion
#                     await asyncio.sleep(1)
#                 else:
#                     return res
#         else:
#             return res

async def __wait_result_music(name, start_time, getFuc):
    while True:
        res = getFuc()
        # print(res)
        if res['data']['name'] == "":
            return res
        if res['data']['status'] == 'run' and res['data']['name'] == name:
            await asyncio.sleep(1)
        else:
            return res

async def __wait_result_motion(name, start_time, getFuc):
    while True:
        res = getFuc()
        # print(res)
        if res['data']['name'] == "":
            return res
        if res['data']['status'] == 'run' and start_time == res['data']['timestamp']:
            await asyncio.sleep(1)
        else:
            return res

async def __wait_result_layer_motion(name, start_time, getFuc):
    while True:
        res = getFuc()
        # print(res)
        find = False
        for i in range(len(res["data"])):
            if res['data'][i]['name'] == str(name + ".layers"):
                if res['data'][i]['status'] == 'run' and start_time == res['data'][i]['timestamp']:
                    await asyncio.sleep(1)
                else:
                    return res
        if find == False:
            return res

async def __wait_result_by_time(time):
    await asyncio.sleep(time)

async def __wait_result_color(type, color, mode, getFuc):

    while True:
        res = getFuc()
        for item in res['data']:
            if item['type'] == type and item['color'] == color and item['mode'] == mode:
                return res
        await asyncio.sleep(1)

async def __wait_result_gait(start_time, type, getFuc):
    while True:
        res = getFuc()
        # print(res)
        if res['data']['timestamp'] == start_time:
            if type == "start": # walking
                if 0 <= res['data']['status'] <= 2:
                    await asyncio.sleep(1)
                else:
                    return res
            #else: # up
            #    if 0 <= res['status'] <= 2:
            #        await asyncio.sleep(1)
            #    else:
            #        return
        elif res['data']['timestamp'] > start_time:
            return res

def __resIsSuccess(res):
    if not isinstance(res,Dict):
        return False
    if not "code" in res:
        return False
    return (res["code"]==0)


@unique
class ChargingState(Enum):
    """机器人充电状态

    :meta private:
    """
    Uncharged = 0
    Charging = 1


@unique
class RobotLanguage(Enum):
    """机器人语言

    :meta private:
    """
    zh = "zh"
    en = "en"
    ko = "ko"


@unique
class RobotButtonLedColor(Enum):
    """机器人胸口按钮灯颜色

    :meta private:
    """
    white = "white"
    red = "red"
    green = "green"
    blue = "blue"
    yellow = "yellow"
    purple = "purple"
    cyan = "cyan"

@unique
class RobotButtonLedMode(Enum):
    """机器人胸口按钮灯模式（开，关，呼吸，闪烁）

    :meta private:
    """
    on = "on"
    off = "off"
    blink = "blink"
    breath = "breath"

@unique
class RobotEyeLedColor(Enum):
    """机器人摄像头指示灯颜色

    :meta private:
    """
    red = "red"
    green = "green"
    blue = "blue"

@unique
class RobotEyeLedMode(Enum):
    """机器人摄像头指示灯模式（开，关，闪烁）

    :meta private:
    """
    on = "on"
    off = "off"
    blink = "blink"


#raise | crouch | stretch | come on | wave | bend | walk | turn around | head | bow
@unique
class RobotBuiltInMotion(Enum):
    """机器人内置动作

    :meta private:
    """
    #重置
    reset = "reset"
    #举手
    handsUp = "raise"
    #蹲下
    crouch = "crouch"
    #挑衅
    comeOn = "come on"
    #舒展手
    stretch = "stretch"
    #摆手
    wave = "wave"
    #弯腿
    bend = "bend"
    #行走
    walk = "walk"
    #转弯
    turnAround = "turn around"
    #转头
    head = "head"
    #鞠躬
    bow = "bow"

@unique
class RobotMotionDirection(Enum):
    """机器人内置动作运行方向

    :meta private:

    Note:
        手腿运动表示左右手或左右腿，头部运动或行走表示运动方向
    """
    none = ""
    left = "left"
    right = "right"
    both = "both"
    forward = "forward"
    backward = "backward"

@unique
class RobotMotionSpeed(Enum):
    """机器人动作运行速度

    :meta private:
    """
    verySlow = "very slow"
    slow = "slow"
    normal = "normal"
    fast = "fast"
    veryFast = "very fast"

@unique
class RobotFaceRecognitionType(Enum):
    """机器人人脸识别类型

    :meta private:
    """
    #头部追踪人脸
    tracking = "tracking"
    #识别是谁
    recognition = "recognition"
    #识别数量
    quantity = "quantity"
    #识别年龄区间
    age_group = "age_group"
    #识别性别
    gender = "gender"
    #识别年龄
    age = "age"
    #识别表情
    expression = "expression"
    #识别口罩
    mask = "mask"
    #识别眼镜
    glass = "glass"


@unique
class RobotJointType(Enum):
    """机器人关节类型
    
    :meta private:
    """
    #右肩转动关节
    No1 = "RightShoulderRoll"
    #右肩弯曲关节
    No2 = "RightShoulderFlex"
    #右肘弯曲关节
    No3 = "RightElbowFlex"
    #左肩转动关节
    No4 = "LeftShoulderRoll"
    #左肩弯曲关节
    No5 = "LeftShoulderFlex"
    #左肘弯曲关节
    No6 = "LeftElbowFlex"
    #右臀部左右转动关节
    No7 = "RightHipLR"
    #右臀部前后转动关节
    No8 = "RightHipFB"
    #右膝盖转动关节
    No9 = "RightKneeFlex"
    #右脚踝前后转动关节
    No10 = "RightAnkleFB"
    #右脚踝左右转动关节
    No11 = "RightAnkleUD"
    #左臀部左右转动关节
    No12 = "LeftHipLR"
    #左臀部前后转动关节
    No13 = "LeftHipFB"
    #左膝盖转动关节
    No14 = "LeftKneeFlex"
    #左脚踝前后转动关节
    No15 = "LeftAnkleFB"
    #左脚踝左右转动关节
    No16 = "LeftAnkleUD"
    #脖子转动关节
    No17 = "NeckLR"


class RobotJointInfo():
    """关节信息

    :meta private:

    Note:
        ID1/2/3/4/5/6/9/10/14/15舵机可运行角度范围为0-180,超出范围运动存在风险.
        ID7舵机可运行角度范围为0-120,超出范围运动存在风险.
        ID8舵机可运行角度范围为10-180,超出范围运动存在风险.
        ID11舵机可运行角度范围为65-180,超出范围运动存在风险.
        ID12舵机可运行角度范围为60-180,超出范围运动存在风险.
        ID13舵机可运行角度范围为0-170,超出范围运动存在风险.
        ID16舵机可运行角度范围为0-115,超出范围运动存在风险.
        ID17舵机可运行角度范围为15-165,超出范围运动存在风险.
    """
    def __init__(self,jointType,angel:int):
        if isinstance(jointType,str):
            strlist = jointType.split('_')	# 用_分割str字符串，并保存到列表
            newStrList = []
            newStr = ""
            if len(strlist)==1:
                newStr = strlist[0]
            else:
                for strItem in strlist:
                    newStrList.append(strItem.title())
                for strItem in newStrList:
                    newStr = newStr+strItem
            self.jointType = RobotJointType(newStr)
        elif isinstance(jointType,RobotJointType):
            self.jointType = jointType
        else:
            raise ValueError("jointType value error")
        self.angel = angel


#动作帧
class RobotActionFrame():
    def __init__(self,actionFrame:Dict):
        self._actionFrame = {}
        for key,value in actionFrame.items():
            jointInfo = RobotJointInfo(key,value)
            self._actionFrame[jointInfo.jointType.value] = jointInfo

    @property
    def interfaceDict(self):
        ret = {}
        for key,value in self._actionFrame.items():
            ret[key] = value.angel
        return ret

    def __getitem__(self, key):
        if not isinstance(key, str):
            return -1
        jointInfo = self._actionFrame.get(key)
        if not jointInfo:
            return -1
        return jointInfo.angel

    def addOrUpdateJointInfo(self,jointInfo:RobotJointInfo):
        self._actionFrame[jointInfo.jointType.value] = jointInfo

    def delJointInfo(self,jointType:RobotJointType):
         self._actionFrame.pop(jointType.value)

class RobotBatteryInfo():
    """机器人电源信息

    :meta private:
    """
    def __init__(self, data=None):
        self._batteryPercentage = 0
        self._chargingState = 0
        self._voltage = 0
        if data:
            self.__dict__ = data
            self._batteryPercentage = data["percent"]
            self._chargingState = data["charging"]
            self._voltage = data["voltage"]

    @property
    def batteryPercentage(self):
        return self._batteryPercentage

    @property
    def chargingState(self):
        return self._chargingState

    @property
    def voltage(self):
        return self._voltage


class RobotVersionInfo():
    """机器人版本信息

    :meta private:
    """
    def __init__(self, data=None):
        self._coreVersion = ""
        self._servoVersion = ""
        self._sn = ""
        if data:
            self.updateWithData(data)

    def updateWithData(self,data:dict):
        if "core" in data:
            self._coreVersion = data["core"]
        if "servo" in data:
            self._servoVersion = data["servo"]
        if "sn" in data:
            self._sn = data["sn"]

    @property
    def core(self):
        return self._coreVersion

    @property
    def servo(self):
        return self._servoVersion

    @property
    def sn(self):
        return self._sn


class RobotLedInfo():
    """机器人LED信息

    :meta private:
    """
    def __init__(self, data=None):
        self._buttonLedColor = ""
        self._buttonLedMode = ""
        self._eyeLedColor = ""
        self._eyeLedMode = ""
        if not data:
            return
        for item in data:
            if item["type"] == "button":
                self._buttonLedColor = item["color"]
                self._buttonLedMode = item["mode"]
            if item["type"] == "camera":
                self._eyeLedColor = item["color"]
                self._eyeLedMode = item["mode"]
    @property
    def buttonLedColor(self):
        return self._buttonLedColor

    @property
    def buttonLedMode(self):
        return self._buttonLedMode

    @property
    def eyeLedColor(self):
        return self._eyeLedColor

    @property
    def eyeLedMode(self):
        return self._eyeLedMode

class RobotAsrResult():
    """机器人ASR识别结果

    :meta private:
    """
    def __init__(self, data=None):
        self._question = ""
        self._answer = ""
        if data:
            self._question = data["intent"]["text"]
            if 'answer' in data["intent"]:
                self._answer = data["intent"]["answer"]["text"]
            else:
                self._answer = "empty"
    @property
    def retDict(self):
        return {"question":self._question,"answer":self._answer}

    @property
    def question(self):
        return self._question

    @property
    def answer(self):
        return self._answer

class RobotVisualTaskResult():
    """机器人视觉识别结果

    :meta private:
    """
    def __init__(self, data=None):
        self._name = ""
        self._color = ""
        self._age = 0
        self._age_group = ""
        self._gender = ""
        self._expression = ""
        self._quantity = 0
        self._mask = ""
        self._glass = ""
        self._gesture = ""
        if not data:
            return
        colorList = data.get("color")
        if colorList!=None and (len(colorList)>0):
            self._color = colorList[0]["name"]
        self._quantity = data.get("quantity",0)
        self._gesture = data.get("gesture","")
        recognitionItem = data.get("recognition")
        if recognitionItem:
            self._name = recognitionItem.get("name","")
        analysisItem = data.get("analysis")
        if not analysisItem:
            return
        self._age = analysisItem.get("age",0)
        self._gender = analysisItem.get("gender","")
        self._age_group = analysisItem.get("group","")
        self._expression = analysisItem.get("expression","")
        self._mask = analysisItem.get("mask","")
        self._glass = analysisItem.get("glass","")

    @property
    def color(self):
        return self._color

    @property
    def recognition(self):
        return self._name

    @property
    def name(self):
        return self._name

    @property
    def age(self):
        return self._age

    @property
    def age_group(self):
        return self._age_group

    @property
    def gender(self):
        return self._gender

    @property
    def expression(self):
        return self._expression

    @property
    def quantity(self):
        return self._quantity

    @property
    def mask(self):
        return self._mask

    @property
    def glass(self):
        return self._glass

    @property
    def gesture(self):
        return self._gesture

######## Yanshee Voice Class ##################################

class Voice(object):
    """通过Voice语音类来控制Yanshee机器人实现语音识别、自然语言处理和TTS等功能的接口类

    """
    def __init__(self):
        pass
    def get_robot_language(self):
        languages_url = basic_url+"devices/languages"
        response = requests.get(url=languages_url, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res
    ################ ASR #############
    def stop_voice_asr(self):
        """停止语音听写

        Returns:
            Dict:
            e.g::
     
                    {
                        code: integer (int32)返回码，0表示正常
                        msg: string提示信息
                    }

        """
        voice_url = basic_url+"voice/iat"
        response = requests.delete(url=voice_url, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def get_voice_asr(self):
        """获取语音听写结果

        Returns:
            Dict:
            e.g::
     
                {
                        code: integer (int32)返回码，0表示正常
                        status: string  idle 非执行状态 run 正在运行
                        timestamp: integer (int32)时间戳, Unix标准时间
                        data:
                                                    {
                                语音听写返回数据
                            }
                        msg:string提示信息
                    }

        """
        voice_url = basic_url+"voice/iat"
        response = requests.get(url=voice_url, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        ret=self.get_robot_language()
        if ret["data"]["language"]=="ko" :
            return res
        res["data"] = json.loads(res["data"].strip(
            b'\x00'.decode()))
        return res

    def start_voice_asr(self,timestamp: int = 0):
        """开始语音听写

        当语音听写处于工作状态时，需要先停止当前的语音听写。

        Args:
            timestamp: integer (int32)时间戳, Unix标准时间

        Returns:
            Dict:
            e.g::
     
                    {
                        "code": 0,
                        "data": {},
                        "msg": "Success"
                    }

        """
        voice_url = basic_url+"voice/iat"
        param = {"timestamp": timestamp}
        json_data = json.dumps(param)
        response = requests.put(url=voice_url, data=json_data, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def sync_do_voice_asr_value(self):
        """执行一次语音听写并获得返回结果(便利方法)。

        Returns:
            str:识别到的内容

        """
        timestamp = int(time.time())
        successed = self.start_voice_asr(timestamp=timestamp)
        if not successed:
            return ""
        coroutine = self.__wait_result(timestamp,self.get_voice_asr)
        loop = asyncio.get_event_loop()
        task = loop.create_task(coroutine)
        loop.run_until_complete(task)
        res = task.result()
        if not self.__resIsSuccess(res):
            logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
            return ""
        ret=self.get_robot_language()
        if ret["data"]["language"]=="ko" :
            return res["data"].strip()
        words = res["data"].get("text")
        if not words:
            return ""
        words = words.get("ws")
        if not words:
            return ""
        result = ""
        for word in words:
            result += word['cw'][0]['w']
        return result

    def sync_do_voice_asr(self):
        """执行一次语音听写并获得返回结果。

        Returns:
            Dict:
            e.g::
     
                    {
                        code: integer (int32)返回码，0表示正常
                        status:string idle 非执行状态run 正在运行
                        timestamp:integer (int32)时间戳, Unix标准时间
                        data:
                           {
                                语音听写返回内容数据
                            }
                        msg: string提示信息
                }

        """
        timestamp = int(time.time())
        res = self.start_voice_asr(timestamp=timestamp)
        if res['code'] != 0:
            logging.error("do voice iat failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
            return res
        coroutine = self.__wait_result(timestamp, self.get_voice_asr)
        loop = asyncio.get_event_loop()
        task = loop.create_task(coroutine)
        loop.run_until_complete(task)
        #result = asyncio.run(coroutine)
        return task.result()

    ################ ASR Offline #############
    def delete_voice_asr_offline_syntax(self,grammar: str):
        """删除指定离线语法名称下的所有配置

        可以先获取系统所有的离线语法名称，请注意系统默认的离线语法名称为defaut, 它不可以通过API来添加，删除以及修改

        Args:
            grammar：需要删除配置的语法名称。

        Returns:
            Dict:
            e.g::
     
                    {
                        code: integer (int32)返回码，0表示正常
                        msg: string提示信息
                    }

        """
        voice_url = basic_url+"voice/asr/offlinesyntax"
        param = {"grammar": grammar}
        json_data = json.dumps(param)
        response = requests.delete(url=voice_url, data=json_data, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def get_voice_asr_offline_syntax(self,grammar: str):
        """获取指定语法名称下的所有配置

        Args:
            grammar(str):需要获得配置结构的语法名称。

        Returns:
            Dict:
            e.g::
     
                    {
                        grammar:string定义语法名称,请输入纯字母
                        slot:
                            [
                                声明槽,内容为字母不数字。所有的操作符及关键词均为半角字符,不支持全角字符。
                                                            {
                                    name:string槽名称
                                }
                            ]
                        start:string     定义开始规则,内容为字母不数字。所有的操作符及关键词均为半角字符,不支持全角字符。
                        startinfo:string 定义开始规则详细内容
                        rule:
                            [
                                所有的离线语法规则
                                                            {
                                    name: string表示规则名称
                                    value:string表示规则内容
                                }
                            ]
                    }
        Examples:
            >>>  res = YanAPI.get_voice_asr_offline_syntax('LocalCmd')
                print(res)
                =======================================================
                {
                    "grammar": "LocalCmd",
                    "rule": [
                                {
                                    "name": "ok",
                                    "value": "我想|我要|请|帮我|我想要|请帮我"
                                }
                            ],
                    "slot": [
                                {
                                    "name": "ok"
                                }
                            ],
                    "start": "LocalCmdStart",
                    "startinfo": "<ok>"
                }

        """
        voice_url = basic_url+"voice/asr/offlinesyntax"
        params = {"body": grammar}
        response = requests.get(url=voice_url, headers=headers, params=params)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def create_voice_asr_offline_syntax(self,object: Dict):
        """创建一个新的离线语法名称配置

        Args:
            object:按照语法规则填写的一个dict。

        Examples:
            e.g:
        >>> {
                "grammar": "LocalCmd",
                "rule": [
                            {
                                "name": "ok",
                                "value": "我想|我要|请|帮我|我想要|请帮我"
                            }，
                            {
                                "name": "hello",
                                "value": "在不在|欢迎"
                            }
                        ],
                "slot": [
                            {
                                "name": "ok"
                            }，
                            {
                                "name": "hello"
                            }
                        ],
                "start": "LocalCmdStart",
                "startinfo": "<ok>|<hello>"
            }

        Returns:
            Dict:
            e.g::
     
                    {
                        "code": 0,
                        "data": {},
                        "msg": "Success"
                    }

        """
        voice_url = basic_url+"voice/asr/offlinesyntax"
        param = object
        json_data = json.dumps(param)
        response = requests.post(url=voice_url, data=json_data, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def update_voice_asr_offline_syntax(self,object: Dict):
        """修改已有语法配置中的命令词和对应的返回值

        可用于配置或添加新的离线命令词

        Args:
            object(Dict):按照语法规则填写的一个dict。（其中的语法名称是需要修改内容的已有名称）

        Examples:

            >>>  {
                    "grammar": "LocalCmd",
                    "rule": [
                                {
                                    "name": "ok",
                                    "value": "我想|我要|请|帮我|我想要|请帮我"
                                }，
                                {
                                    "name": "hello",
                                    "value": "在不在|欢迎"
                                }
                            ],
                    "slot": [
                                {
                                    "name": "ok"
                                }，
                                {
                                    "name": "hello"
                                }
                            ],
                    "start": "LocalCmdStart",
                    "startinfo": "<ok>|<hello>"
                }

        Returns:
            Dict:
            e.g::
     
                    {
                        "code": 0,
                        "data": {},
                        "msg": "Success"
                    }

        """
        voice_url = basic_url+"voice/asr/offlinesyntax"
        param = object
        json_data = json.dumps(param)
        response = requests.put(url=voice_url, data=json_data, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def get_voice_asr_offline_syntax_grammars(self):
        """获取所有离线语法名称

        Note:
            请注意系统默认的离线语法名称为defaut, 它不可以通过API来添加，删除以及修改。

        Returns:
            Dict:
            e.g::
     
                    {
                        "grammar": [
                                        {
                                            "name": "default"
                                        },
                                        {
                                            "name": String 离线语法名称
                                        }
                                    ]
                    }

        """
        voice_url = basic_url+"voice/asr/offlinesyntax/grammars"
        response = requests.get(url=voice_url, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    ################ NLP #############
    def stop_voice_nlp(self):
        """停止语音识别服务

        Returns:
            Dict:
            e.g::
     
                    {
                        "code": 0,
                        "data": {},
                        "msg": "Success"
                    }

        """
        voice_url = basic_url+"voice/asr"
        response = requests.delete(url=voice_url, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def get_voice_nlp_state(self):
        """获取语义理解工作状态

        Returns:
            Dict:
            e.g::
     
                    {
                        code: 0,
                        status:string  idle 非执行状态 run 正在运行
                        timestamp:integer (int32)时间戳, Unix标准时间
                        data:
                                                    {
                                语音返回数据
                            }
                        msg: string提示信息
                    }

        """
        voice_url = basic_url+"voice/asr"
        response = requests.get(url=voice_url, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        ret=self.get_robot_language()
        if ret["data"]["language"]=="ko" :
            return res
        dataStr = res["data"].strip(b'\x00'.decode())
        res["data"] = json.loads(dataStr)
        return res

    def start_voice_nlp(self,continues=False, timestamp=0):
        """开始语义理解

        当语义理解(单次/多次)处于工作状态时，需要先停止当前的语义理解。

        Args:
            continues:boolean是否进行连续语意识别, 布尔值, true 需要， false不需要, 默认为false
            timestamp:integer (int32)时间戳, Unix标准时间

        Returns:
            Dict:
            e.g::
     
                    {
                        "code": 0,
                        "data": {},
                        "msg": "Success"
                    }

        """
        voice_url = basic_url+"voice/asr"
        param = {"continues": continues, "timestamp": timestamp}
        json_data = json.dumps(param)
        response = requests.put(url=voice_url, data=json_data, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def sync_do_voice_nlp_value(self):
        """执行一次语义理解并获得返回结果(便利方法)

        Returns:
            Dict:
            e.g::

                {
                    question:""#识别的内容
                    answer:""  #回答的内容
                }

        """
        timestamp = int(time.time())
        self.start_voice_nlp(timestamp=timestamp)
        coroutine = self.__wait_result(timestamp,self.get_voice_nlp_state)
        loop = asyncio.get_event_loop()
        tasks = loop.create_task(coroutine)
        loop.run_until_complete(tasks)
        res = tasks.result()
        if  not self.__resIsSuccess(res):
            logging.error("error code = %d msg = %s",res.get("code",-1),res.get("msg",""))
            return None
        ret=self.get_robot_language()
        if ret["data"]["language"]=="ko" :
            return res["data"]
        return RobotAsrResult(res["data"]).retDict

    def sync_do_voice_nlp(self):
        """执行一次语义理解并获得返回结果

        Returns:
            Dict:
            e.g::
     
                    {
                        code: integer (int32)返回码，0表示正常
                        status:string idle 非执行状态run 正在运行
                        timestamp:integer (int32)时间戳, Unix标准时间
                        data:
                            {
                                语义理解返回内容数据
                            }
                        msg: string提示信息
                    }

        """
        timestamp = int(time.time())
        self.start_voice_nlp(timestamp=timestamp)
        coroutine = self.__wait_result(timestamp, self.get_voice_nlp_state)
        # result = asyncio.run(coroutine)

        loop = asyncio.get_event_loop()
        tasks = loop.create_task(coroutine)
        loop.run_until_complete(tasks)
        return tasks.result()

    ################ TTS #############
    def stop_voice_tts(self):
        """停止语音播报任务

        Returns:
            Dict:
            e.g::
     
                    {
                        "code": 0,
                        "data": {},
                        "msg": "Success"
                    }

        """
        voice_url = basic_url+"voice/tts"
        response = requests.delete(url=voice_url, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def get_voice_tts_state(self,timestamp: int = None):
        """获取指定或者当前工作状态

        带时间戳为指定任务工作状态，如果无时间戳则当前任务。

        Args:
            timestamp(int64):时间戳

        Returns:
            Dict:
            e.g::
     
                    {
                        code: integer (int32)返回码，0表示正常
                        status: string
                        当任务为语语音合成的时候，状态如下：idle 任务不存在,run 播放该段语音,build 正在合成该段语音,wait 处于等待执行状态
                        timestamp: integer (int32)时间戳, Unix标准时间
                        data:
                             {
                                语音返回数据
                            }
                        msg:string提示信息
                    }

        """
        voice_url = basic_url+"voice/tts"
        response = requests.get(url=voice_url, headers=headers)
        if timestamp != None:
            params = {'timestamp': timestamp}
            response = requests.get(url=voice_url, headers=headers, params=params)
        res = json.loads(str(response.content.decode("utf-8")))
        res["data"] = json.loads(
            str(res["data"].strip(b'\x00'.decode())))
        return res

    def start_voice_tts(self,tts: str = "", interrupt: bool = True, timestamp: int = 0):
        """开始语音合成任务

        合成指定的语句并播放。当语音合成处于工作状态时可以接受新的语音合成任务.

        Args:
            tts(str):待合成的文字
            interrupt(bool):是否可以被打断，默认为True
            timestamp(int):时间戳, Unix标准时间

        Returns:
            Dict:
            e.g::
     
                    {
                        "code": 0,
                        "data": {},
                        "msg": "Success"
                    }

        """
        voice_url = basic_url+"voice/tts"
        param = {"tts": tts, "interrupt": interrupt, "timestamp": timestamp}
        json_data = json.dumps(param)
        response = requests.put(url=voice_url, data=json_data, headers=headers)
        res = json.loads(str(response.content.decode("utf-8")))
        return res

    def sync_do_tts(self,tts: str = "", interrupt: bool = True):
        """执行语音合成任务,合成完成后返回

        Args:
            tts(str):待合成的文字
            interrupt(bool):是否可以被打断，默认为True

        Returns:
            Dict:
            e.g::
     
                    {
                        code: integer (int32)返回码，0表示正常
                        status: string
                        当任务为语语音合成的时候，状态如下：idle 任务不存在,run 播放该段语音,build 正在合成该段语音,wait 处于等待执行状态
                        timestamp: integer (int32)时间戳, Unix标准时间
                        data:
                            {
                                语音返回数据
                            }
                        msg:string提示信息
                    }

        """
        t = int(time.time())
        res = self.start_voice_tts(tts = tts, interrupt = interrupt, timestamp = t)
        if res['code'] != 0:
            logging.error("do tts failed error code = %d msg = %s",res.get("code",-1),res.get("msg","unknow error"))
            return res
        coroutine = self.__wait_result_common(timestamp=t, getFuc=self.get_voice_tts_state, args=(t,))
        loop = asyncio.get_event_loop()
        tasks = loop.create_task(coroutine)
        loop.run_until_complete(tasks)
        # Success Example
        return tasks.result()

    async def __wait_result(self,timestamp, getFuc):
        while True:
            res = getFuc()
            if(timestamp == res["timestamp"]):
                status = res["status"]
                if status == "idle":
                    return res
                else:
                    await asyncio.sleep(1)

    async def __wait_result_common(self,timestamp, getFuc, args=()):
        while True:
            res = getFuc(*args)
            # print(res)
            if(timestamp == res["timestamp"]):
                status = res["status"]
                if status == "idle":
                    return res
                else:
                    await asyncio.sleep(1)

    def __resIsSuccess(self,res):
        if not isinstance(res,Dict):
            return False
        if not "code" in res:
            return False
        return (res["code"]==0)

######## Yanshee control uKit2.0 API ##################################

class ukit_controller:
    """通过Yanshee机器人控制uKit2.0的接口类

    """

    send_ip = "255.255.255.255"
    recv_ip = "0.0.0.0"
    port = 25880
    buffsize = 1024
    udp_send_socket = socket(AF_INET, SOCK_DGRAM)
    udp_send_socket.setsockopt(SOL_SOCKET, SO_BROADCAST, 1)
    udp_recv_socket = socket(AF_INET, SOCK_DGRAM)
    udp_recv_socket.setsockopt(SOL_SOCKET, SO_BROADCAST, 1)

    def __init__(self):
        self.send_addr = (self.send_ip,self.port)
        self.recv_addr = (self.recv_ip,self.port)

    def __auto_find_broadcast_ip(self):
        ipstr = '([0-9]{1,3}\.){3}[0-9]{1,3}'
        ipconfig_process = subprocess.Popen("ifconfig", stdout=subprocess.PIPE)
        output = (ipconfig_process.stdout.read())
        broad_pattern = re.compile('(broadcast %s)' % ipstr)
        pattern = re.compile(ipstr)
        broadlist = []
        for broadaddr in broad_pattern.finditer(str(output)):
            broad = pattern.search(broadaddr.group())
            broadlist.append(broad.group())
        return broadlist

    def creat_channel_to_ukit(self,port = 0):
        """设置yanshee UDP广播通道的ukit端口号

        Args:
            port(int):设置uKit2.0的广播监听端口号，0~100,默认为0，对应实际25880~25979端口

        """
        port = port + 25880
        if(port > 25979)or(port < 25880):
            print("PORT is out of rang for 0~100")
            return 0
        self.port = port
        ip_list = self.__auto_find_broadcast_ip()
        if len(ip_list) > 0:
            self.send_ip = ip_list[0]
        self.send_addr = (self.send_ip,self.port)
        self.recv_addr = (self.recv_ip,self.port)
        self.udp_recv_socket.bind(self.recv_addr)

    def set_broadcast_ip(self,ip):
        """设置yanshee UDP广播地址

        Args:
            ip(str):机器人广播地址

        """
        self.send_ip = ip

    def set_recv_ip(self,ip):
        """设置yanshee UDP广播接收地址

        Args:
            ip(str):机器人广播接收地址

        """
        self.recv_ip = ip

    def send_msg_to_ukit(self,msg):
        """发送UDP广播消息给ukit2.0

        Args:
            msg(str):发送给uKit2.0的广播消息的内容

        """
        print("send_msg_to_ukit: " + msg)
        ip_list = self.__auto_find_broadcast_ip()
        if len(ip_list) > 0:
            self.send_ip = ip_list[0]
        self.send_addr = (self.send_ip,self.port)
        data_msg = lib_send.lib_send_data_to_uKit(msg)
        print("send_data_to_ukit: " + data_msg)
        #将每个十六进制都转成单独的bytes发给Ukit
        self.udp_send_socket.sendto(bytes.fromhex(data_msg), self.send_addr)

    def get_msg_from_ukit(self):
        """接收UDP广播消息

        Returns:
            recv_msg(str):接收到广播消息的内容

        """
        data, addr = self.udp_recv_socket.recvfrom(self.buffsize)
        #解析接收到的数据帧
        recv_msg = lib_send.lib_get_msg_from_uKit(data.hex())
        return recv_msg
    def close_channel_to_ukit(self):
        """关闭yanshee UDP广播通道

        """
        self.udp_send_socket.close()
        self.udp_recv_socket.close()


#if __name__ == '__main__':
    #color = "OrAnge"
    #detect_color = color.lower()
    #flag = False
    #try:
    #    res = do_control_vision_once(option="color", type="color_detect")
    #    print(res)
    #    for item in res['data']['color']:
    #        if item['name'] == detect_color:
    #            flag = True
    #            break
    #except:
    #    flag = False
    #return flag
    #print(flag)
    #touched = 0
    #try:
    #    res = get_sensors_touch(33)
    #    print(res)
    #    for item in res['data']['touch']:
            # We need our id to be identical
    #        if item['id'] == 33:
    #            touched = item['value']
    #            break
    #except:
    #    touched = 0
    #print(touched)
    #result = False
    #hear = ""
    #test = "你好"
    #try:
        # In Blockly we only grab first one, although our API support all slots
        #do_motion_gait_once(speed_v=3, speed_h=0, period=2, wave=False, steps=10)
        #print(res)
        #if res is not None:
        #    for ws in res['data']['text']['ws']:
        #        print(ws)
        #        for cw in ws['cw']:
        #            print(cw)
        #            hear = hear + cw['w']
        #    print(hear)
        #if hear == test:
        #    result = True
    #except:
        #result = False
        #print('bad program')
    #print(result)
    #print(res)
    #return result

