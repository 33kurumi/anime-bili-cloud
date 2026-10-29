package com.abc.sourceservice.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * SSRF 防护（流代理的「任意 URL 读取」闸门）。
 *
 * <p>威胁模型：{@code /api/source/proxy?u=<任意 URL>} 本质是一个后端 GET 代理，
 * 如果不校验，攻击者可以让服务器去读内网服务（Nacos 8848、MySQL、云厂商 metadata 169.254.169.254），
 * 这就是典型的 SSRF。这里做三层校验：
 * <ol>
 *   <li>协议白名单：只允许 http/https（挡掉 file/gopher/ftp 等）；</li>
 *   <li>主机名黑名单：localhost / *.local / *.internal / metadata.*；</li>
 *   <li>DNS 解析后逐 IP 判定：回环、私网、链路本地、CGNAT、组播、保留段一律拒绝。</li>
 * </ol>
 *
 * <p>注意：解析与真正请求之间仍有 TOCTOU 窗口（DNS rebinding）。彻底修法是
 * 把解析出的 IP 钉死再发请求（HttpClient 自定义 DNS），本项目受 hutool 封装限制未做，
 * 但已把风险面从「任意内网」收敛到「先通过校验的公网域名」，答辩时如实说明即可。
 */
@Slf4j
@Component
public class SsrfGuard {

    /** 主机名直接拒绝（不解析）——这些名字天然指向本机/内网。 */
    private static final String[] BLOCKED_HOST_SUFFIXES = {
            "localhost", ".localhost", ".local", ".internal", ".intranet"
    };
    private static final String[] BLOCKED_HOST_NAMES = {
            "metadata", "metadata.google.internal", "instance-data", "169.254.169.254"
    };

    /**
     * 校验 URL 合法且不指向内网。
     * @return 解析后的 URI（调用方可直接用，避免二次解析出分歧）
     * @throws SsrfBlockedException 不合法/命中内网
     */
    public URI assertAllowed(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SsrfBlockedException("URL 为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new SsrfBlockedException("URL 格式非法: " + rawUrl);
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new SsrfBlockedException("URL 缺少协议: " + rawUrl);
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new SsrfBlockedException("只允许 http/https，拒绝协议: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("URL 缺少主机名: " + rawUrl);
        }
        host = host.toLowerCase(Locale.ROOT);
        // URI.getHost() 对 IPv6 字面量返回 "[::1]"（带方括号）
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (isBlockedHostName(host)) {
            throw new SsrfBlockedException("禁止访问本机/内网主机名: " + host);
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new SsrfBlockedException("域名解析失败: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new SsrfBlockedException("域名无解析结果: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new SsrfBlockedException("禁止访问内网/回环地址: "
                        + address.getHostAddress() + "（主机 " + host + "）");
            }
        }
        return uri;
    }

    /** 主机名黑名单。 */
    static boolean isBlockedHostName(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String suffix : BLOCKED_HOST_SUFFIXES) {
            if (h.equals(suffix) || h.endsWith(suffix)) return true;
        }
        for (String name : BLOCKED_HOST_NAMES) {
            if (h.equals(name)) return true;
        }
        return false;
    }

    /** IP 判定（public static 便于单测直接喂 IP 字面量，不触发网络）。 */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address == null) return true;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isBlockedIpv4(bytes);
        if (bytes.length == 16) return isBlockedIpv6(bytes);
        return true;
    }

    /** 0/8、10/8、100.64/10、127/8、169.254/16、172.16/12、192.0.0/24、192.168/16、198.18/15、224/4 及以上保留段。 */
    static boolean isBlockedIpv4(byte[] b) {
        int a0 = b[0] & 0xFF, a1 = b[1] & 0xFF, a2 = b[2] & 0xFF;
        if (a0 == 0 || a0 == 10 || a0 == 127) return true;
        if (a0 == 100 && (a1 & 0xC0) == 64) return true;          // 100.64.0.0/10 CGNAT
        if (a0 == 169 && a1 == 254) return true;                   // 链路本地（含云 metadata）
        if (a0 == 172 && a1 >= 16 && a1 <= 31) return true;        // 172.16.0.0/12
        if (a0 == 192 && a1 == 0 && a2 == 0) return true;          // 192.0.0.0/24
        if (a0 == 192 && a1 == 168) return true;                   // 192.168.0.0/16
        if (a0 == 198 && (a1 == 18 || a1 == 19)) return true;      // 198.18.0.0/15 基准测试
        if (a0 >= 224) return true;                                // 组播 + 240/4 保留
        return false;
    }

    /** ::1、::、fc00::/7 ULA、fe80::/10、ff00::/8、IPv4 映射/兼容地址里的内网 IPv4。 */
    static boolean isBlockedIpv6(byte[] b) {
        boolean allZero = true;
        for (byte value : b) {
            if (value != 0) { allZero = false; break; }
        }
        if (allZero) return true;
        boolean loopback = true;
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) { loopback = false; break; }
        }
        if (loopback && b[15] == 1) return true;

        int first = b[0] & 0xFF;
        if ((first & 0xFE) == 0xFC) return true;                  // fc00::/7 唯一本地地址
        if (first == 0xFE && (b[1] & 0xC0) == 0x80) return true;  // fe80::/10 链路本地
        if (first == 0xFF) return true;                           // 组播

        // ::ffff:a.b.c.d（IPv4-mapped）
        boolean mapped = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) { mapped = false; break; }
        }
        if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            return isBlockedIpv4(new byte[]{b[12], b[13], b[14], b[15]});
        }
        // ::a.b.c.d（IPv4-compatible，已废弃但仍可被利用）
        boolean compatible = true;
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) { compatible = false; break; }
        }
        if (compatible) {
            return isBlockedIpv4(new byte[]{b[12], b[13], b[14], b[15]});
        }
        return false;
    }
}
