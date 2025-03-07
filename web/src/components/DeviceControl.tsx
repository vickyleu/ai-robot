import React, { useState } from 'react';
import { Device, DeviceCommand } from '@airobot/core';
import { CruzrMoveCommand, CruzrSpeakCommand, CruzrGestureCommand, CruzrExpressionCommand } from '@airobot/device/cruzr';

interface DeviceControlProps {
    device: Device;
}

export const DeviceControl: React.FC<DeviceControlProps> = ({ device }) => {
    const [moveDirection, setMoveDirection] = useState('forward');
    const [moveSpeed, setMoveSpeed] = useState(1.0);
    const [speakText, setSpeakText] = useState('');
    const [gesture, setGesture] = useState('wave');
    const [expression, setExpression] = useState('smile');

    const handleMove = async () => {
        try {
            const command = new CruzrMoveCommand(moveDirection, moveSpeed);
            await device.sendCommand(command);
        } catch (error) {
            console.error('移动命令执行失败:', error);
        }
    };

    const handleSpeak = async () => {
        if (!speakText) return;
        try {
            const command = new CruzrSpeakCommand(speakText);
            await device.sendCommand(command);
        } catch (error) {
            console.error('语音命令执行失败:', error);
        }
    };

    const handleGesture = async () => {
        try {
            const command = new CruzrGestureCommand(gesture);
            await device.sendCommand(command);
        } catch (error) {
            console.error('手势命令执行失败:', error);
        }
    };

    const handleExpression = async () => {
        try {
            const command = new CruzrExpressionCommand(expression);
            await device.sendCommand(command);
        } catch (error) {
            console.error('表情命令执行失败:', error);
        }
    };

    return (
        <div className="p-4 space-y-6">
            <h2 className="text-xl font-bold mb-4">设备控制 - {device.name}</h2>
            
            {/* 移动控制 */}
            <div className="space-y-2">
                <h3 className="font-semibold">移动控制</h3>
                <div className="flex space-x-4 items-center">
                    <select
                        value={moveDirection}
                        onChange={(e) => setMoveDirection(e.target.value)}
                        className="border rounded px-2 py-1"
                    >
                        <option value="forward">前进</option>
                        <option value="backward">后退</option>
                        <option value="left">左转</option>
                        <option value="right">右转</option>
                    </select>
                    <input
                        type="range"
                        min="0"
                        max="1"
                        step="0.1"
                        value={moveSpeed}
                        onChange={(e) => setMoveSpeed(parseFloat(e.target.value))}
                        className="w-32"
                    />
                    <span className="text-sm text-gray-500">{moveSpeed.toFixed(1)}</span>
                    <button
                        onClick={handleMove}
                        className="bg-blue-500 text-white px-4 py-2 rounded hover:bg-blue-600"
                    >
                        执行
                    </button>
                </div>
            </div>

            {/* 语音控制 */}
            <div className="space-y-2">
                <h3 className="font-semibold">语音控制</h3>
                <div className="flex space-x-4 items-center">
                    <input
                        type="text"
                        value={speakText}
                        onChange={(e) => setSpeakText(e.target.value)}
                        placeholder="请输入要说的话"
                        className="border rounded px-2 py-1 flex-1"
                    />
                    <button
                        onClick={handleSpeak}
                        className="bg-blue-500 text-white px-4 py-2 rounded hover:bg-blue-600"
                    >
                        说话
                    </button>
                </div>
            </div>

            {/* 手势控制 */}
            <div className="space-y-2">
                <h3 className="font-semibold">手势控制</h3>
                <div className="flex space-x-4 items-center">
                    <select
                        value={gesture}
                        onChange={(e) => setGesture(e.target.value)}
                        className="border rounded px-2 py-1"
                    >
                        <option value="wave">挥手</option>
                        <option value="bow">鞠躬</option>
                        <option value="dance">跳舞</option>
                    </select>
                    <button
                        onClick={handleGesture}
                        className="bg-blue-500 text-white px-4 py-2 rounded hover:bg-blue-600"
                    >
                        执行
                    </button>
                </div>
            </div>

            {/* 表情控制 */}
            <div className="space-y-2">
                <h3 className="font-semibold">表情控制</h3>
                <div className="flex space-x-4 items-center">
                    <select
                        value={expression}
                        onChange={(e) => setExpression(e.target.value)}
                        className="border rounded px-2 py-1"
                    >
                        <option value="smile">微笑</option>
                        <option value="happy">开心</option>
                        <option value="sad">伤心</option>
                        <option value="angry">生气</option>
                    </select>
                    <button
                        onClick={handleExpression}
                        className="bg-blue-500 text-white px-4 py-2 rounded hover:bg-blue-600"
                    >
                        执行
                    </button>
                </div>
            </div>
        </div>
    );
};