package bin.mt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * 可以写个网页接口，判断Online文件是否存在即可知道微信是否在线
     * 经过测试直接关闭控制台后ShutdownHook不会运行，所以该情况下Online文件不会自动删除
     */
    private static final File ONLINE_FILE = new File("Online");

    private static final Thread SHUTDOWN_HANDLER = new Thread(() -> {
        if (ONLINE_FILE.exists())
            //noinspection ResultOfMethodCallIgnored
            ONLINE_FILE.delete();
    });

    public static void main(String[] args) throws IOException {
        Runtime.getRuntime().addShutdownHook(SHUTDOWN_HANDLER);
        if (ONLINE_FILE.exists())
            //noinspection ResultOfMethodCallIgnored
            ONLINE_FILE.delete();
        final WeChat weChat = new WeChat();
        logger.info("正在获取登录二维码..");
        weChat.login(new WeChat.LoginListener() {
            ImageViewer viewerFrame;

            @Override
            public void onReceiveQRCode(byte[] jpgData) {
                logger.info("获取成功，请用手机微信扫码");
                viewerFrame = new ImageViewer(jpgData);
            }

            @Override
            public void onQRCodeScanned(byte[] jpgData) {
                logger.info("扫码成功，请在手机微信中点击登录");
                if (viewerFrame != null) {
                    viewerFrame.setImage(jpgData);
                }
            }

            @Override
            public void onLoginResult(boolean loginSucceed) {
                if (viewerFrame != null) {
//                    viewerFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                    viewerFrame.dispose();
                    viewerFrame = null;
                }
                if (loginSucceed) {
                    long time = System.currentTimeMillis();
                    logger.info("登录成功");
                    try {
                        if (!ONLINE_FILE.createNewFile()) {
                            logger.info("创建Online文件失败");
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    w:
                    while (true) {
                        for (int i = 0; i < 10; i++) {
                            try {
                                if (weChat.syncCheck() < 1000)
                                    continue w;
                            } catch (Throwable e) {
                                logger.debug("SyncCheck", e);
                                continue w;
                            }
                        }
                        // 如果10次都错误，break
                        break;
                    }
                    //noinspection ResultOfMethodCallIgnored
                    ONLINE_FILE.delete();
                    if (System.currentTimeMillis() - time > 5000) {
                        if (Email.sendEmail("921558445@qq.com", "微信离线通知", "服务器的微信已经离线啦，快去登录！"))
                            logger.info("微信已离线，发送通知邮件成功");
                        else
                            logger.info("微信已离线，发送通知邮件失败");
                    }
                } else {
                    logger.info("登录失败");
                    //noinspection ResultOfMethodCallIgnored
                    ONLINE_FILE.delete();
                }
            }

            @Override
            public void onException(IOException e) {
                e.printStackTrace();
                if (viewerFrame != null) {
                    viewerFrame.dispose();
                    viewerFrame = null;
                }
            }
        });
        Scanner scanner = new Scanner(System.in);
        while (true) {
            if (scanner.nextLine().equalsIgnoreCase("exit")) {
                System.exit(0);
                break;
            }
        }
    }
}
