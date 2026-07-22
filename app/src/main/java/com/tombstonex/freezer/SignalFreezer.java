package com.tombstonex.freezer;

import android.os.Process;
import com.tombstonex.util.Logger;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SignalFreezer implements IFreezer {

    private final int stopSignal;
    private static final int CONT_SIGNAL = 18;

    public SignalFreezer(boolean useSigTstp) {
        this.stopSignal = useSigTstp ? 20 : 19;
    }

    @Override
    public boolean freeze(int pid, int uid) {
        try {
            Process.sendSignal(pid, stopSignal);
            // M-27: 轮询重试机制替代硬编码 10ms 等待，最多尝试 5 次。
            for (int attempt = 0; attempt < 5; attempt++) {
                Thread.sleep(10);
                String state = readProcessState(pid);
                if ("T".equals(state)) {
                    Logger.d("Signal 冻结: pid=" + pid + " signal=" + stopSignal);
                    return true;
                }
            }
            // M-27: 5 次重试后仍未达到目标状态，记录告警并回滚。
            String finalState = readProcessState(pid);
            Logger.w("SignalFreezer: 冻结验证失败 state=" + finalState + "（已重试 5 次），回滚 SIGCONT pid=" + pid);
            Process.sendSignal(pid, CONT_SIGNAL);
            return false;
        } catch (InterruptedException e) {
            // M6-修复: 恢复中断标志，避免吞掉中断信号
            Thread.currentThread().interrupt();
            Logger.e("Signal 冻结被中断 pid=" + pid, e);
            try { Process.sendSignal(pid, CONT_SIGNAL); } catch (Exception ignored) {}
            return false;
        } catch (Exception e) {
            Logger.e("Signal 冻结失败 pid=" + pid, e);
            // L3: 清理 —— 信号可能已发送，发送 SIGCONT 确保进程回到可调度状态。
            try { Process.sendSignal(pid, CONT_SIGNAL); } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * 读取 /proc/pid/status 的 State 字段。
     * SIGSTOP/SIGTSTP 冻结后进程进入停止态，State 字段首字符为 "T"。
     *
     * @return State 字段的首字符（如 "T" 表示停止），读取失败返回 null
     */
    private String readProcessState(int pid) {
        File file = new File("/proc/" + pid + "/status");
        if (!file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("State:")) {
                    // 格式: "State: T (stopped)"
                    String rest = line.substring("State:".length()).trim();
                    if (rest.isEmpty()) return null;
                    return rest.substring(0, 1);
                }
            }
        } catch (IOException e) {
            Logger.w("SignalFreezer: 读取 /proc/" + pid + "/status 失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public boolean unfreeze(int pid, int uid) {
        try {
            Process.sendSignal(pid, CONT_SIGNAL);
            // M-31: 验证解冻结果，与 freeze() 保持一致的验证逻辑。
            for (int attempt = 0; attempt < 5; attempt++) {
                Thread.sleep(10);
                String state = readProcessState(pid);
                if (state != null && !"T".equals(state)) {
                    Logger.d("Signal 解冻: pid=" + pid);
                    return true;
                }
            }
            String finalState = readProcessState(pid);
            Logger.w("SignalFreezer: 解冻验证失败 state=" + finalState + "（已重试 5 次）pid=" + pid);
            return false;
        } catch (InterruptedException e) {
            // M6-修复: 恢复中断标志，避免吞掉中断信号
            Thread.currentThread().interrupt();
            Logger.e("Signal 解冻被中断 pid=" + pid, e);
            return false;
        } catch (Exception e) {
            Logger.e("Signal 解冻失败 pid=" + pid, e);
            return false;
        }
    }

    @Override
    public String getName() {
        return "Signal(" + (stopSignal == 19 ? "SIGSTOP" : "SIGTSTP") + ")";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}