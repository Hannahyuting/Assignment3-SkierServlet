import com.google.gson.Gson;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.swagger.client.model.LiftRide;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.concurrent.EventCountCircuitBreaker;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@WebServlet(name = "SkierServlet", value = "/SkierServlet")
public class SkierServlet extends HttpServlet {

    private static final int NUM_CHANS = 100;
    private static final int WAIT_TIME_SECS = 1;
    private static final String QUEUE_NAME = "skiersInfo";
    private static final String SERVER = "35.165.112.73";
    private GenericObjectPool<Channel> pool;
    EventCountCircuitBreaker breaker = new EventCountCircuitBreaker(1000, 1, TimeUnit.SECONDS, 800);

    public void init() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(SERVER);
        factory.setUsername("yutingz");
        factory.setPassword("yutingz");
        factory.setVirtualHost("vhost");
        factory.setPort(5672);

        final Connection conn;
        try {
            conn = factory.newConnection();
            GenericObjectPoolConfig config = new GenericObjectPoolConfig();
            config.setMaxTotal(NUM_CHANS);
            config.setBlockWhenExhausted(true);
            config.setMaxWait(Duration.ofSeconds(WAIT_TIME_SECS));
            RMQChannelFactory channelFactory = new RMQChannelFactory(conn);
            pool = new GenericObjectPool<>(channelFactory, config);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
        }

    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.getWriter().write("It works!");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        Gson gson = new Gson();
        String urlPath = request.getPathInfo();

        // check we have a URL!
        if (urlPath == null || urlPath.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write("missing parameters");
            return;
        }

        String[] urlParts = urlPath.split("/");
        if (!isUrlValid(urlParts)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        } else {
            LiftRide liftRide = gson.fromJson(IOUtils.toString(request.getReader()), LiftRide.class);
            RequestData requestData = new RequestData(
                    Integer.parseInt(urlParts[1]),
                    urlParts[3],
                    urlParts[5],
                    Integer.parseInt(urlParts[7]),
                    liftRide);
            String payload = gson.toJson(requestData);

            if (breaker.incrementAndCheckState()) {
                try {
                    Channel channel = pool.borrowObject();
                    if (channel != null) {
                        channel.queueDeclare(QUEUE_NAME, false, false, false, null);
                        channel.basicPublish("", QUEUE_NAME, null, payload.getBytes(StandardCharsets.UTF_8));
                        pool.returnObject(channel);
                        response.setStatus(HttpServletResponse.SC_CREATED);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            }
        }
    }

    private boolean isUrlValid(String[] urlPath) {
        int dayId = Integer.parseInt(urlPath[5]);
        if (!isInteger(urlPath[1]) || !isInteger(urlPath[7]) || dayId < 1 || dayId > 366) {
            return false;
        }

        if (urlPath.length != 8 || !urlPath[2].equals("seasons")
                || !urlPath[4].equals("days") || !urlPath[6].equals("skiers")) {
            return false;
        }

        return true;
    }

    private boolean isInteger(String val) {
        try {
            Integer.parseInt(val);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

