package com.todoapp.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * INF-B2 补充：{@code @IntegrationTest} + {@code RANDOM_PORT}（BE-2 并发测试会用的形态）。
 * 证明真实端口模式下基座照常生效，且 Tomcat 只监听回环（AD-14）。
 */
@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("INF-B2 RANDOM_PORT 模式")
class RandomPortFrameworkTest {

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    @Autowired
    private DatabaseCleaner cleaner;

    @Test
    @DisplayName("基座照常生效：冻结时钟已复位、连的是测试库")
    void infrastructureStillApplies() {
        assertThat(clock.instant()).isEqualTo(MutableClock.DEFAULT_INSTANT);
        assertThat(cleaner.connectedPort()).isEqualTo(TestDatabase.get().getPort());
    }

    @Test
    @DisplayName("经 127.0.0.1 可以访问真实端口")
    void reachableOnLoopback() throws Exception {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/__framework_probe__"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("本机的局域网地址上连不到这个端口（只监听回环）")
    void notReachableOnLanAddress() {
        Optional<InetAddress> lan = firstNonLoopbackIpv4();
        assumeTrue(lan.isPresent(), "本机没有非回环 IPv4 地址，无法从局域网侧验证");

        assertThatThrownBy(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(lan.get(), port), 2000);
            }
        }).isInstanceOf(IOException.class);
    }

    private static Optional<InetAddress> firstNonLoopbackIpv4() {
        try {
            return NetworkInterface.networkInterfaces()
                    .filter(RandomPortFrameworkTest::isUp)
                    .flatMap(NetworkInterface::inetAddresses)
                    .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress())
                    .findFirst();
        } catch (SocketException e) {
            return Optional.empty();
        }
    }

    private static boolean isUp(NetworkInterface nic) {
        try {
            return nic.isUp() && !nic.isLoopback();
        } catch (SocketException e) {
            return false;
        }
    }
}
