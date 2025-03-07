import React, { useEffect, useState } from 'react';
import { Device, DeviceStatus } from '@airobot/core';
import { DeviceManager } from '@airobot/core';

interface DeviceListProps {
    deviceManager: DeviceManager;
}

export const DeviceList: React.FC<DeviceListProps> = ({ deviceManager }) => {
    const [availableDevices, setAvailableDevices] = useState<Device[]>([]);
    const [connectedDevices, setConnectedDevices] = useState<Device[]>([]);

    useEffect(() => {
        // 监听可用设备列表变化
        const availableDevicesSubscription = deviceManager.availableDevices.subscribe(devices => {
            setAvailableDevices(devices);
        });

        // 监听已连接设备列表变化
        const connectedDevicesSubscription = deviceManager.connectedDevices.subscribe(devices => {
            setConnectedDevices(devices);
        });

        // 开始扫描设备
        deviceManager.startDiscovery();

        return () => {
            availableDevicesSubscription.unsubscribe();
            connectedDevicesSubscription.unsubscribe();
            deviceManager.stopDiscovery();
        };
    }, [deviceManager]);

    const handleConnect = async (device: Device) => {
        try {
            await device.connect();
        } catch (error) {
            console.error('连接设备失败:', error);
        }
    };

    const handleDisconnect = async (device: Device) => {
        try {
            await device.disconnect();
        } catch (error) {
            console.error('断开设备失败:', error);
        }
    };

    const renderDeviceStatus = (status: DeviceStatus) => {
        switch (status) {
            case DeviceStatus.CONNECTED:
                return <span className="text-green-500">已连接</span>;
            case DeviceStatus.CONNECTING:
                return <span className="text-yellow-500">连接中</span>;
            case DeviceStatus.DISCONNECTED:
                return <span className="text-gray-500">未连接</span>;
            case DeviceStatus.ERROR:
                return <span className="text-red-500">错误</span>;
            default:
                return null;
        }
    };

    return (
        <div className="p-4">
            <h2 className="text-xl font-bold mb-4">设备列表</h2>
            <div className="space-y-4">
                {availableDevices.map(device => (
                    <div key={device.deviceId} className="border p-4 rounded-lg">
                        <div className="flex justify-between items-center">
                            <div>
                                <h3 className="text-lg font-semibold">{device.name}</h3>
                                <p className="text-sm text-gray-500">ID: {device.deviceId}</p>
                                <p className="text-sm text-gray-500">类型: {device.type}</p>
                                <p className="text-sm">
                                    状态: {renderDeviceStatus(device.status.value)}
                                </p>
                            </div>
                            <div>
                                {device.status.value === DeviceStatus.DISCONNECTED && (
                                    <button
                                        onClick={() => handleConnect(device)}
                                        className="bg-blue-500 text-white px-4 py-2 rounded hover:bg-blue-600"
                                    >
                                        连接
                                    </button>
                                )}
                                {device.status.value === DeviceStatus.CONNECTED && (
                                    <button
                                        onClick={() => handleDisconnect(device)}
                                        className="bg-red-500 text-white px-4 py-2 rounded hover:bg-red-600"
                                    >
                                        断开
                                    </button>
                                )}
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
};