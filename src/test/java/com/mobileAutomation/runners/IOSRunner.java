package com.mobileAutomation.runners;

import com.mobileAutomation.config.DeviceConfig;
import com.mobileAutomation.config.IOSConfig;
import com.mobileAutomation.dataproviders.UserDataProvider;
import com.mobileAutomation.driver.DriverManager;
import com.mobileAutomation.flows.LoginFlow;
import com.mobileAutomation.flows.LoginResult;
import com.mobileAutomation.pages.InvalidLoginDialog;
import com.mobileAutomation.pages.SecretPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.appium.java_client.ios.IOSDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.testng.Assert;
import org.testng.annotations.*;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class IOSRunner extends DriverManager {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(IOSRunner.class);

    private LoginFlow loginFlow;

    @BeforeClass
    @Parameters({"appiumPort", "udid", "wdaLocalPort"})
    public void setup(String appiumPort, String udid, String wdaLocalPort) throws MalformedURLException {
        validateSimulatorUdid(udid);

        IOSConfig config = DeviceConfig.load("ios.json", IOSConfig.class);

        DesiredCapabilities options = new DesiredCapabilities();
        options.setCapability("platformName", "iOS");
        options.setCapability("appium:automationName", "XCUITest");

        options.setCapability("appium:udid", udid);
        options.setCapability("appium:deviceName", udid);
        options.setCapability("appium:platformVersion", config.platformVersion);

        // installs app ONCE at session start
        options.setCapability("appium:app",
                System.getProperty("user.dir") + config.app);

        /* ---------- PARALLEL STABILITY (CRITICAL) ---------- */

        options.setCapability("appium:wdaLocalPort", Integer.parseInt(wdaLocalPort));
        options.setCapability("appium:derivedDataPath",
                System.getProperty("user.dir") + "/wda/" + udid);

        /* ---------- THE REAL FIX ---------- */

        // Build WDA per simulator to avoid prebuilt collisions in parallel
        options.setCapability("appium:usePrebuiltWDA", false);
        options.setCapability("appium:useNewWDA", true);

        // prevents Xcode internal deadlocks in parallel runs
        options.setCapability("appium:shouldUseSingletonTestManager", false);

        // extra headroom for WDA startup under parallel load
        options.setCapability("appium:wdaStartupRetries", 3);
        options.setCapability("appium:wdaStartupRetryInterval", 10000);
        options.setCapability("appium:wdaLaunchTimeout", 120000);

        // we handle resets manually
        options.setCapability("appium:noReset", false);
        options.setCapability("appium:fullReset", false);

        /* ---------- QUALITY OF LIFE ---------- */

        options.setCapability("appium:autoAcceptAlerts", true);
        options.setCapability("appium:newCommandTimeout", 300);

        if (isSimulatorUdid(udid)) {
            options.setCapability("appium:updatedWDABundleId", wdaBundleIdFor(udid));
        }

        if (Boolean.parseBoolean(System.getProperty("showXcodeLog", "false"))) {
            options.setCapability("appium:showXcodeLog", true);
        }


        setDriver(new IOSDriver(new URL("http://127.0.0.1:" + appiumPort), options));

        loginFlow = new LoginFlow();
    }

    @BeforeMethod
    public void prepareState() {
        // optional: implement a logout/home navigation flow here if needed
        logger.info("Preparing app state for next test");
        terminateApp();
        activateApp();
    }

    @Test(dataProvider = "usersData", dataProviderClass = UserDataProvider.class)
    public void runTests(String username, String password) {

        logger.info("STEP: User logs in as '{}'", username);
        LoginResult loginResult = loginFlow.login(username, password);

        if (loginResult instanceof LoginResult.Invalid invalid) {
            logger.info("STEP: Expect invalid login alert");

            InvalidLoginDialog dialog = invalid.dialog();
            if (dialog.isVisible()) {
                dialog.accept();
            } else {
                logger.info("Invalid login alert not visible; staying on login page");
            }
        } else {
            logger.info("STEP: Verifying secret area is displayed");

            LoginResult.Success success = (LoginResult.Success) loginResult;
            SecretPage secretPage = success.page();
            Assert.assertTrue(secretPage.isVisible());
            Assert.assertTrue(secretPage.loggedInUserText().contains(username), "Expected username to be displayed");
            secretPage.logOut();
        }
    }

    @AfterClass
    public void teardown() {
        quit();
    }

    private void terminateApp() {
        getDriver().executeScript("mobile: terminateApp", Map.of("bundleId", IOS_BUNDLE_ID));
    }

    private void activateApp() {
        getDriver().executeScript("mobile: activateApp", Map.of("bundleId", IOS_BUNDLE_ID));
    }

    private void validateSimulatorUdid(String udid) {
        if (udid == null || udid.isBlank()) {
            throw new IllegalArgumentException("UDID must not be blank. Update src/test/resources/testng-mobile-parallel.xml");
        }

        // Simulator UDIDs are UUIDs with hyphens. Real devices are typically hex strings without hyphens.
        if (!isSimulatorUdid(udid)) {
            return;
        }

        SimctlDevices devices = SimctlDevices.load();
        List<SimctlDevices.SimDevice> available = devices.availableDevices();

        boolean found = available.stream().anyMatch(d -> d.udid.equalsIgnoreCase(udid));
        if (!found) {
            String sample = available.stream()
                    .limit(8)
                    .map(d -> String.format("%s (%s) - %s", d.name, d.udid, d.runtime))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("No available simulators found. Run: xcrun simctl list devices");

            throw new IllegalArgumentException(
                    "Unknown iOS simulator UDID: " + udid + "\n" +
                    "Available simulators:\n" + sample + "\n" +
                    "Update src/test/resources/testng-mobile-parallel.xml to match."
            );
        }
    }

    private boolean isSimulatorUdid(String udid) {
        return udid.contains("-");
    }

    private String wdaBundleIdFor(String udid) {
        String suffix = udid.replace("-", "").toLowerCase();
        if (suffix.length() > 10) {
            suffix = suffix.substring(suffix.length() - 10);
        }
        return "com.daniellotem.WebDriverAgentRunner." + suffix;
    }

    private static class SimctlDevices {
        private final List<SimDevice> devices;

        private SimctlDevices(List<SimDevice> devices) {
            this.devices = devices;
        }

        public List<SimDevice> availableDevices() {
            List<SimDevice> available = new ArrayList<>();
            for (SimDevice device : devices) {
                if (device.isAvailable) {
                    available.add(device);
                }
            }
            return available;
        }

        public static SimctlDevices load() {
            String output = run("xcrun", "simctl", "list", "devices", "-j");
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(output);
                JsonNode devicesNode = root.path("devices");

                List<SimDevice> devices = new ArrayList<>();
                Iterator<Map.Entry<String, JsonNode>> fields = devicesNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String runtime = entry.getKey();
                    for (JsonNode deviceNode : entry.getValue()) {
                        SimDevice device = new SimDevice(
                                deviceNode.path("name").asText(),
                                deviceNode.path("udid").asText(),
                                deviceNode.path("isAvailable").asBoolean(true),
                                runtime
                        );
                        devices.add(device);
                    }
                }
                return new SimctlDevices(devices);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to parse simctl devices. Run `xcrun simctl list devices -j` to verify.",
                        e
                );
            }
        }

        private static String run(String... command) {
            try {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                byte[] bytes = process.getInputStream().readAllBytes();
                int exit = process.waitFor();
                String output = new String(bytes, StandardCharsets.UTF_8);
                if (exit != 0) {
                    throw new IllegalStateException("Command failed: " + String.join(" ", command) + "\n" + output);
                }
                return output;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to run: " + String.join(" ", command), e);
            }
        }

        private static class SimDevice {
            private final String name;
            private final String udid;
            private final boolean isAvailable;
            private final String runtime;

            private SimDevice(String name, String udid, boolean isAvailable, String runtime) {
                this.name = name;
                this.udid = udid;
                this.isAvailable = isAvailable;
                this.runtime = runtime;
            }
        }
    }
}
