
package snowboyPiper

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 示例主函数
 */
fun snowboyPiper() = runBlocking {
    println("启动Snowboy关键词检测与Piper语音合成Demo")
    println("该Demo将使用麦克风监听关键词，检测到关键词后会播放\"你好\"")
    println("按Ctrl+C终止程序")

    val demo = SnowboyPiperDemo()
    println("初始化检测器...")
    val initSuccess = demo.initialize()
    if (!initSuccess) {
        println("初始化失败！")
        return@runBlocking
    }

    println("初始化成功，开始关键词检测...")
    demo.startDetection()

    // 主流程等待一段时间（如30秒），实际可根据需求调整
    delay(30000)
    println("停止检测...")
    demo.stopDetection()
    demo.release()
    println("检测流程结束。")
}