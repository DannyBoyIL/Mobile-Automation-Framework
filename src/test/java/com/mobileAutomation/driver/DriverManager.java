package com.mobileAutomation.driver;

import io.appium.java_client.AppiumDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DriverManager {

    private static final ThreadLocal<AppiumDriver> DRIVER = new ThreadLocal<>();
    private static final Logger logger = LoggerFactory.getLogger(DriverManager.class);

    public static void setDriver(AppiumDriver driver) {
        logger.debug("Setting driver for thread: {}", Thread.currentThread().getName());
        DRIVER.set(driver);
    }

    public static AppiumDriver getDriver() {
        return DRIVER.get();
    }

    public static void unload() {
        logger.debug("Unloading driver for thread: {}", Thread.currentThread().getName());
        DRIVER.remove();
    }
}
