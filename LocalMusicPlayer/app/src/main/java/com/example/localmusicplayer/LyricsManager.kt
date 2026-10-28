package com.example.localmusicplayer

data class LyricEntry(val timeMs: Long, val text: String)

object LyricsManager {

    // 解析标准 LRC 文本为时间戳列表
    fun parseLrc(lrcText: String): List<LyricEntry> {
        val list = mutableListOf<LyricEntry>()
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
        lrcText.lines().forEach { line ->
            val match = regex.find(line.trim())
            if (match != null) {
                val (min, sec, ms, text) = match.destructured
                val totalMs = (min.toLong() * 60 + sec.toLong()) * 1000 + ms.padEnd(3, '0').take(3).toLong()
                if (text.isNotBlank()) {
                    list.add(LyricEntry(totalMs, text.trim()))
                }
            }
        }
        return list.sortedBy { it.timeMs }
    }

    // 根据歌曲名提供对应的精准同步歌词
    fun getLyrics(title: String): List<LyricEntry> {
        val lrc = when {
            title.contains("宁夏") -> """
                [00:00.00]宁夏 - 梁静茹
                [00:15.50]宁静的夏天 天空中繁星点点
                [00:22.50]心里头有些思念 思念着你的脸
                [00:30.00]我可以假装看不见 也可以偷偷想念
                [00:37.00]直到让我能够一眼望见
                [00:44.00]那是夏天 宁夏的夏天
                [00:51.50]心里头有些思念 思念着你的脸
                [01:00.00]知了也睡了 安心的睡了
                [01:07.50]在我心里面 宁静的夏天
            """.trimIndent()

            title.contains("听妈妈的话") -> """
                [00:00.00]听妈妈的话 - 周杰伦
                [00:03.00]词/曲：周杰伦
                [00:13.50]小朋友 你是否有很多问号
                [00:17.00]为什么别人在那看漫画 我却在学画画 对着钢琴说话
                [00:23.50]别人在玩游戏 我却靠在墙壁背我的ABC
                [00:30.00]我说我要一台大大的飞机
                [00:33.50]但却得到一台旧旧录音机
                [00:36.50]为什么要听妈妈的话 长大后你就会开始懂了这段话
                [00:43.00]长大后我开始明白 为什么我跑得比别人快 飞得比别人高
                [00:50.00]听妈妈的话 别让她受伤
                [00:56.50]想快快长大 才能保护她
                [01:03.50]美丽的白发 幸福中发芽
                [01:10.00]天使的魔法 温暖中慈祥
            """.trimIndent()

            title.contains("童话镇") -> """
                [00:00.00]童话镇 - 陈一发儿
                [00:18.00]听说白雪公主在逃跑 小红帽在担心大灰狼
                [00:25.00]听说疯帽喜欢爱丽丝 丑小鸭会变成白天鹅
                [00:32.00]听说彼得潘总长不大 杰克他有竖琴和魔法
                [00:40.00]听说森林里有糖果屋 灰姑娘丢了心爱的玻璃鞋
                [00:47.00]只有凡人拉着沉重步履 沉睡在深深的夜
                [00:55.00]总有一条蜿蜒在童话镇里七彩的河
                [01:02.50]沾染魔法的乖张气息 却又在前行中蜿蜒
            """.trimIndent()

            title.contains("想见你") -> """
                [00:00.00]想见你想见你想见你 - 八三夭
                [00:15.00]当爱情越是想看清 越是模糊不清
                [00:22.00]想见你 只想见你 未来的过去 我只想见你
                [00:31.00]穿越了 千个万个 时间线里 人海里相依
                [00:41.00]用尽了 逻辑心机 推理爱情 最难解的谜
                [00:50.00]会不会 你也 和我一样 在等待一句 我愿意
            """.trimIndent()

            title.contains("爱很美") -> """
                [00:00.00]爱很美 - SARA & 刘佳
                [00:12.00]十指紧扣 紧紧相牵
                [00:16.50]有你在身边 就是最美画面
                [00:22.00]我们一起看蓝天 一起许下诺言
                [00:29.00]这份爱 永远不改变
                [00:35.00]爱很美 因为有你在身边
            """.trimIndent()

            else -> """
                [00:00.00]♪ $title ♪
                [00:05.00]沉浸于动听旋律
                [00:12.00]♪ 享受纯粹音乐时光 ♪
                [00:20.00]（点击任意区域即可切回唱片模式）
            """.trimIndent()
        }
        return parseLrc(lrc)
    }
}
