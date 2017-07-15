package bin.mt;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.BASE64Decoder;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static bin.mt.Util.*;

@SuppressWarnings("WeakerAccess")
public class WeChat {
    private static final Logger logger = LoggerFactory.getLogger(WeChat.class);

    static {
        System.setProperty("jsse.enableSNIExtension", "false");
    }

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.86 Safari/537.36";

    private String uuid;
    private String loginUrl;
    private String domainName;
    private String pushDomainName;
    private String skey;
    private String wxsid;
    private String wxuin;
    private String pass_ticket;
    private String syncKey;
    private JSONObject syncKeyJson;

    private CookieJar cookieJar = new CookieJar() {
        HashMap<String, List<Cookie>> cookieStore = new HashMap<>();

        @Override
        public void saveFromResponse(@Nonnull HttpUrl url, @Nonnull List<Cookie> cookies) {
            cookieStore.put(url.host(), cookies);
        }

        @Override
        public List<Cookie> loadForRequest(@Nonnull HttpUrl url) {
            List<Cookie> cookies = new ArrayList<>();
            String host = url.host();
            // 没有对path进行匹配
            for (Map.Entry<String, List<Cookie>> entry : cookieStore.entrySet()) {
                if (host.endsWith(entry.getKey())) {
                    cookies.addAll(entry.getValue());
                }
            }
            return cookies;
        }

    };
    private OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar).build();
    // 禁止重定向
    private OkHttpClient client2 = new OkHttpClient.Builder()
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar).followRedirects(false).build();


    private byte[] getLoginQRCode() throws IOException {
        String url = "https://wx.qq.com/";
        Request request = new Request.Builder().url(url)
                .addHeader("accept", "*/*")
                .addHeader("connection", "Keep-Alive")
                .addHeader("user-agent", UA)
                .build();
        Response response = client.newCall(request).execute();
        response.close();

        url = "https://login.wx.qq.com/jslogin?appid=wx782c26e4c19acffb&redirect_uri=https%3A%2F%2Fwx2.qq.com%2Fcgi-bin%2Fmmwebwx-bin%2Fwebwxnewloginpage&fun=new&lang=zh_CN&_=" + System.currentTimeMillis();
        request = new Request.Builder().url(url)
                .addHeader("accept", "*/*")
                .addHeader("connection", "Keep-Alive")
                .addHeader("user-agent", UA)
                .build();

        response = client.newCall(request).execute();
        checkStatusCode(response);
        //noinspection ConstantConditions
        String str = response.body().string();
        response.close();
        uuid = getStringMiddle(str, "window.QRLogin.uuid = \"", "\";");

        url = "https://login.weixin.qq.com/qrcode/" + uuid;
        request = new Request.Builder().url(url)
                .addHeader("accept", "*/*")
                .addHeader("connection", "Keep-Alive")
                .addHeader("user-agent", UA)
                .build();
        response = client.newCall(request).execute();
        checkStatusCode(response);
        //noinspection ConstantConditions
        byte[] data = response.body().bytes();
        response.close();
        return data;
    }

    private boolean checkQRCode(LoginListener listener) throws IOException {
        String url = "https://login.wx.qq.com/cgi-bin/mmwebwx-bin/login?loginicon=true&uuid=" + uuid + "&tip=0&r=-1722694480&_=" + System.currentTimeMillis();
        Request request = new Request.Builder().url(url)
                .addHeader("accept", "*/*")
                .addHeader("connection", "Keep-Alive")
                .addHeader("user-agent", UA)
                .addHeader("host", "login.wx.qq.com")
                .addHeader("referer", "https://wx.qq.com/")
                .build();
        Response response = client.newCall(request).execute();
        checkStatusCode(response);
        //noinspection ConstantConditions
        String str = response.body().string();
        response.close();
        String code = getStringMiddle(str, "code=", ";");
        switch (code) {
            case "408":
                break;
            case "201":
                String base64 = getStringMiddle(str, "base64,", "'");
                listener.onQRCodeScanned(new BASE64Decoder().decodeBuffer(base64));
                break;
            case "200":
                loginUrl = getStringMiddle(str, "redirect_uri=\"", "\"");
                domainName = getStringMiddle(loginUrl, "//", "/");
                pushDomainName = "webpush." + domainName;
                return true;
            default:
                logger.debug("checkQRCode: unknown code - " + str);
                return false;
        }
        return false;
    }

    private boolean isLogged() throws IOException {
        Request request = new Request.Builder().url(loginUrl)
                .addHeader("accept", "*/*")
                .addHeader("connection", "Keep-Alive")
                .addHeader("user-agent", UA)
                .build();
//        System.out.println("loginUrl " + loginUrl);
        Response response = client2.newCall(request).execute();
        //noinspection ConstantConditions
        String str = response.body().string();
        response.close();
//        System.out.println(str);
        if (getStringMiddle(str, "<ret>", "</ret>").equals("0")) {
            skey = getStringMiddle(str, "<skey>", "</skey>");
            wxsid = getStringMiddle(str, "<wxsid>", "</wxsid>");
            wxuin = getStringMiddle(str, "<wxuin>", "</wxuin>");
            pass_ticket = getStringMiddle(str, "<pass_ticket>", "</pass_ticket>");
//            System.out.println(skey);
//            System.out.println(wxsid);
//            System.out.println(wxuin);
//            System.out.println(pass_ticket);
//            System.out.println(webwx_data_ticket);
            loadSyncKey();
            return true;
        }
        return false;
    }

    public void login(LoginListener listener) {
        new Thread(() -> {
            try {
                byte[] data = getLoginQRCode();
                listener.onReceiveQRCode(data);
                while (true) {
                    if (checkQRCode(listener))
                        break;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                listener.onLoginResult(isLogged());
            } catch (IOException e) {
                listener.onException(e);
            }
        }).start();
    }

    private void loadSyncKey() throws IOException {
        String postData = "{\"BaseRequest\":{\"Uin\":\"" + wxuin
                + "\",\"Sid\":\"" + wxsid + "\",\"Skey\":\"" + skey
                + "\",\"DeviceID\":\"" + get15RandomText() + "\"}}";
        String url = "https://" + domainName + "/cgi-bin/mmwebwx-bin/webwxinit?r=-1747024806&lang=zh_CN&pass_ticket=" + pass_ticket;
        Request request = new Request.Builder().url(url)
                .method("POST", RequestBody.create(MediaType.parse("application/json;charset=UTF-8"), postData))
                .addHeader("accept", "*/*")
                .addHeader("connection", "Keep-Alive")
                .addHeader("user-agent", UA)
                .addHeader("host", "wx.qq.com")
                .addHeader("content-type", "application/json;charset=UTF-8")
                .addHeader("referer", "https://" + domainName + "/")
                .build();

        Response response = client.newCall(request).execute();

        //noinspection ConstantConditions
        JSONObject jsonObject = JSONObject.fromObject(response.body().string());
        response.close();
        jsonObject = syncKeyJson = jsonObject.getJSONObject("SyncKey");
        int count = jsonObject.getInt("Count");
        JSONArray jsonArray = jsonObject.getJSONArray("List");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            jsonObject = jsonArray.getJSONObject(i);
            sb.append("|").append(jsonObject.getInt("Key")).append("_").append(jsonObject.getInt("Val"));
        }
        sb.deleteCharAt(0);
        syncKey = sb.toString();
//        System.out.println(syncKey);
//        System.out.println(syncKeyJson.toString());
    }

    public int syncCheck() throws IOException {
        String url = "https://" + pushDomainName + "/cgi-bin/mmwebwx-bin/synccheck?r=" + System.currentTimeMillis()
                + "&skey=" + skey.replace("@", "%40") + "&sid=" + wxsid + "&uin=" + wxuin + "&deviceid=" + get15RandomText()
                + "&synckey=" + syncKey.replace("|", "%7C") + "&_=" + System.currentTimeMillis();

//        System.out.println("正在等待消息..");

        Request request = new Request.Builder().url(url)
                .addHeader("accept", "*/*")
                .addHeader("connection", "Keep-Alive")
                .addHeader("user-agent", UA)
                .addHeader("referer", "https://" + domainName + "/")
                .addHeader("host", pushDomainName)
                .build();

        Response response;
        try {
            response = client.newCall(request).execute();
        } catch (Exception e) {
            return 1100;
        }
        if (!response.isSuccessful()) {
            response.close();
            return 1100;
        }

        //noinspection ConstantConditions
        String str = response.body().string();
        response.close();
        String retCode = getStringMiddle(str, "retcode:\"", "\"");
        String selector = getStringMiddle(str, "selector:\"", "\"");

        // 1101 1100 1102掉线
        if (retCode.length() == 4 && retCode.startsWith("1")) {
            logger.warn(str);
            return Integer.parseInt(retCode);
        }
        if (selector.equals("0"))
            return 0;
        // 有消息
        try {
            getMessage();
        } catch (Throwable e) {
            return 1000;
        }
        return 1;
    }

    private void getMessage() throws IOException {
        JSONObject json = new JSONObject();

        JSONObject baseRequest = new JSONObject();
        baseRequest.put("Uin", wxuin);
        baseRequest.put("Sid", wxsid);
        baseRequest.put("Skey", skey);
        baseRequest.put("DeviceID", get15RandomText());
        json.put("BaseRequest", baseRequest);
        json.put("SyncKey", syncKeyJson);
        StringBuilder sb = new StringBuilder("-1728");
        for (int i = 0; i < 6; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        json.put("rr", Integer.valueOf(sb.toString()));
        String content = json.toString();
//        System.out.println(content);

        String url = "https://" + domainName + "/cgi-bin/mmwebwx-bin/webwxsync?sid=" + wxsid + "&skey=" + skey.replace("@", "%40") + "&pass_ticket=" + pass_ticket;
        Request request = new Request.Builder().url(url)
                .method("POST", RequestBody.create(MediaType.parse("application/json;charset=UTF-8"), content))
                .addHeader("connection", "keep-alive")
                .addHeader("accept", "application/json, text/plain, */*")
                .addHeader("user-agent", UA)
                .addHeader("content-type", "application/json;charset=UTF-8")
                .build();


        Response response;
        try {
            response = client.newCall(request).execute();
        } catch (Exception e) {
            return;
        }
        if (!response.isSuccessful()) {
            response.close();
            return;
        }

        //noinspection ConstantConditions
        content = response.body().string();
        response.close();
//        System.out.println(content);

        JSONObject jsonObject = JSONObject.fromObject(content);
        JSONObject syncKeyJson = this.syncKeyJson = jsonObject.getJSONObject("SyncKey");
        int count = syncKeyJson.getInt("Count");
        JSONArray jsonArray = syncKeyJson.getJSONArray("List");
        sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            syncKeyJson = jsonArray.getJSONObject(i);
            sb.append("|").append(syncKeyJson.getInt("Key")).append("_").append(syncKeyJson.getInt("Val"));
        }
        sb.deleteCharAt(0);
        syncKey = sb.toString();

        jsonArray = jsonObject.getJSONArray("AddMsgList");
        int size = jsonArray.size();
        for (int i = 0; i < size; i++) {
            jsonObject = jsonArray.getJSONObject(i);
//            String fromUserName = jsonObject.getString("FromUserName");
//            String toUserName = jsonObject.getString("ToUserName");
            int msgType = jsonObject.getInt("MsgType");
            String con = jsonObject.getString("Content");
            if (msgType == 49) {
                checkPay(con);
            }
        }
    }

    private boolean checkPay(String con) throws IOException {
        if (!con.contains("CDATA[微信支付]") || !con.contains("CDATA[二维码收款到账") || !con.contains("收款成功"))
            return false;
        String money = getStringMiddle(con, "二维码收款到账", "元");
        if (money.isEmpty())
            return false;
        try {
            //noinspection ResultOfMethodCallIgnored
            Float.parseFloat(money);
        } catch (NumberFormatException e) {
            return false;
        }
        String mark = getStringMiddle(con, "付款方留言：", "<br/>");
        String time = getStringMiddle(con, "到账时间：", "<br/>");
        logger.info("二维码收款：{}元，备注：{}", money, mark.isEmpty() ? "无" : mark);

        // 下面是收到转账后处理，业务代码不公开，请改成你自己的
        MtUtil.openVip(mark, money, time);
        return true;
    }

    private static void checkStatusCode(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("Http status code = " + response.code());
        }
    }

    interface LoginListener {
        /**
         * 得到登录二维码
         *
         * @param jpgData 二维码图片
         */
        void onReceiveQRCode(byte[] jpgData);

        /**
         * 二维码被扫描
         *
         * @param jpgData 头像
         */
        void onQRCodeScanned(byte[] jpgData);

        void onLoginResult(boolean loginSucceed);

        void onException(IOException e);
    }

}
