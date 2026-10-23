package com.zoujuexian.aiagentdemo.config;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志文件输出配置验证单测
 */
@SpringBootTest
public class LoggingConfigTest {

    private static final Logger log = LoggerFactory.getLogger(LoggingConfigTest.class);

    @Test
    void test01_verifyLogFileCreationAndWriting() throws Exception {
        String probeToken = "PROBE_TOKEN_" + System.currentTimeMillis();
        log.info("【日志落盘验证探针】这是写入日志文件的验证探针: {}", probeToken);

        // 验证文件是否已在 logs 目录下生成
        File logFile = new File("logs/aiagentdemo.log");
        assertTrue(logFile.exists(), "日志文件 logs/aiagentdemo.log 必须自动生成在项目根目录");

        // 验证日志内容中包含刚刚打印的探针标记
        String content = Files.readString(logFile.toPath());
        assertTrue(content.contains(probeToken), "日志文件中必须成功记录刚刚打印的探针日志");
        System.out.println(">>> 日志文件落盘验证通过！路径: " + logFile.getAbsolutePath() + "，当前大小: " + logFile.length() + " 字节");
    }
}
