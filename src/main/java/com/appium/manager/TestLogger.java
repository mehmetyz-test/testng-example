package com.appium.manager;

import com.annotation.values.Author;
import com.appium.entities.MobilePlatform;
import com.appium.filelocations.FileLocations;
import com.appium.utils.Helpers;
import com.epam.reportportal.service.ReportPortal;
import com.video.recorder.AppiumScreenRecordFactory;
import com.video.recorder.IScreenRecord;
import io.cucumber.java.Scenario;
import org.openqa.selenium.logging.LogEntry;
import org.testng.ITestResult;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.logging.Level;

/**
 * Created by saikrisv on 24/01/17.
 */
public class TestLogger extends Helpers {

    private File logFile;
    private ThreadLocal<List<LogEntry>> logEntries = new ThreadLocal<>();
    private ThreadLocal<PrintWriter> log_file_writer = new ThreadLocal<>();
    private ScreenShotManager screenShotManager;
    private String videoPath;
    private Map<String, Integer> scenarios = new HashMap<String,Integer>();


    private String getVideoPath() {
        return videoPath;
    }

    private void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public TestLogger() {
        screenShotManager = new ScreenShotManager();
    }

    private File createLogsDir(String dirName, String fileName){
        logFile = new File(System.getProperty("user.dir") + FileLocations.DEVICE_LOGS_DIRECTORY + dirName
                + fileName + ".txt");
        if(!logFile.exists()){
            try {
                logFile.getParentFile().mkdirs();
                logFile.createNewFile();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return logFile;
    }

    protected void startLogging(ITestResult iTestResult)
            throws IOException, InterruptedException {
        String methodName = iTestResult.getMethod().getMethodName();
        String className = iTestResult.getTestClass()
            .getRealClass().getSimpleName();

        if (isNativeAndroid()) {
            String udid = AppiumDeviceManager.getAppiumDevice().getDevice().getUdid();
            List<LogEntry> logcat = AppiumDriverManager.getDriver().manage()
                .logs().get("logcat").filter(Level.ALL);
            logEntries.set(logcat);
            logFile = new File(System.getProperty("user.dir") + FileLocations.ADB_LOGS_DIRECTORY
                    + udid + "__" + methodName + ".txt");
            log_file_writer.set(new PrintWriter(logFile));
        }
        if ("true".equalsIgnoreCase(System.getenv("VIDEO_LOGS"))) {
            IScreenRecord videoRecording = AppiumScreenRecordFactory.recordScreen();
            videoRecording.startVideoRecording(className, methodName, methodName);
        }
        setDescription(iTestResult);
    }

    public void startLogging(String testName) throws IOException {
        String scenarioName = testName.replaceAll(" ", "_");
        if(scenarios.containsKey(scenarioName)) {
            scenarios.put(scenarioName, scenarios.get(scenarioName)+1);
        }else{
            scenarios.put(scenarioName,1);
        }
        int runCounter = scenarios.get(scenarioName);
        if (isNativeAndroid()) {
            String udid = AppiumDeviceManager
                    .getAppiumDevice()
                    .getDevice()
                    .getUdid();
            List<LogEntry> logcat = AppiumDriverManager
                    .getDriver()
                    .manage()
                    .logs()
                    .get("logcat").filter(Level.ALL);
            logEntries.set(logcat);
            createLogsDir(scenarioName,"/" + udid + "-run-" + runCounter);
            log_file_writer.set(new PrintWriter(logFile));
        }
//        if ("true".equalsIgnoreCase(System.getenv("VIDEO_LOGS"))) {
//            IScreenRecord videoRecording = AppiumScreenRecordFactory.recordScreen();
//            videoRecording.startVideoRecording(testName, testName, testName);
//        }
        //setDescription(testName);
    }

    private void setDescription(ITestResult iTestResult) {
        Optional<String> originalDescription = Optional.ofNullable(iTestResult
            .getMethod().getDescription());
        String description = "Platform: " + AppiumDeviceManager.getMobilePlatform()
            + " UDID: " + AppiumDeviceManager.getAppiumDevice()
            .getDevice().getUdid()
            + " Name: " + AppiumDeviceManager.getAppiumDevice()
            .getDevice().getName()
            + " Host: " + AppiumDeviceManager.getAppiumDevice().getHostName();
        Author annotation = iTestResult.getMethod().getConstructorOrMethod().getMethod()
            .getAnnotation(Author.class);
        if (annotation != null) {
            description += "\nAuthor: " + annotation.name();
        }
        if (originalDescription.isPresent()
            && !originalDescription.get()
            .contains(AppiumDeviceManager.getAppiumDevice()
                .getDevice().getUdid())) {
            iTestResult.getMethod().setDescription(originalDescription.get()
                + "\n" + description);
        } else {
            iTestResult.getMethod().setDescription(description);
        }
    }


    protected HashMap<String, String> endLogging(ITestResult result, String deviceModel)
            throws Exception {
        HashMap<String, String> logs = new HashMap<>();
        String className = result.getInstance().getClass().getSimpleName();
        stopViewRecording(result, className);
        if (isNativeAndroid()) {
            String adbPath = System.getProperty("user.dir") + FileLocations.ADB_LOGS_DIRECTORY
                    + AppiumDeviceManager.getAppiumDevice().getDevice().getUdid()
                    + "__" + result.getMethod().getMethodName() + ".txt";
            logs.put("adbLogs", adbPath);
            logEntries.get().forEach(logEntry -> {
                log_file_writer.get().println(logEntry);
            });
            log_file_writer.get().close();
            ReportPortal.emitLog("ADB Logs", "DEBUG", new Date(), new File(adbPath));
        }
        /*
         * Failure Block
         */
        handleTestFailure(result, className, deviceModel);
        String baseHostUrl = "http://" + getHostMachineIpAddress() + ":"
                + getRemoteAppiumManagerPort(AppiumDeviceManager
                .getAppiumDevice().getHostName());
        if ("true".equalsIgnoreCase(System.getenv("VIDEO_LOGS"))) {
            setVideoPath("screenshot/" + AppiumDeviceManager.getMobilePlatform()
                    .toString().toLowerCase()
                    + "/" + AppiumDeviceManager.getAppiumDevice().getDevice().getUdid()
                    + "/" + className + "/" + result.getMethod()
                    .getMethodName() + "/" + result.getMethod().getMethodName() + ".mp4");

            String videoPath = System.getProperty("user.dir")
                    + FileLocations.OUTPUT_DIRECTORY + getVideoPath();
            if (new File(videoPath).exists()) {
                ReportPortal.emitLog("Video Logs", "Trace", new Date(), new File(videoPath));
                logs.put("videoLogs", baseHostUrl + "/" + getVideoPath());
            }
        }
        String failedScreen = screenShotManager.getFailedScreen();
        String framedFailureScreen = screenShotManager.getFramedFailedScreen();

        if (result.getStatus() == ITestResult.FAILURE) {
            String screenShotFailure = null;
            try {
                screenShotFailure = baseHostUrl;
            } catch (Exception e) {
                e.printStackTrace();
            }
            String screenFailure = System.getProperty("user.dir")
                    + FileLocations.OUTPUT_DIRECTORY + failedScreen;
            if (new File(screenFailure).exists()) {
                screenShotFailure = screenShotFailure
                        + "/" + failedScreen;
                logs.put("screenShotFailure", screenShotFailure);
            } else {
                String framedScreenFailure = System.getProperty("user.dir")
                        + FileLocations.OUTPUT_DIRECTORY + framedFailureScreen;
                if (new File(framedScreenFailure).exists()) {
                    screenShotFailure = screenShotFailure
                            + "/" + framedScreenFailure;
                    logs.put("screenShotFailure", screenShotFailure);
                }
            }
        }
        return logs;
    }

    public HashMap<String, String> endLogging(String testName, String testResult)
            throws Exception {
        HashMap<String, String> logs = new HashMap<>();
        //stopViewRecording(result, className);
        if (isNativeAndroid()) {
            String adbPath = System.getProperty("user.dir") + FileLocations.DEVICE_LOGS_DIRECTORY
                    + AppiumDeviceManager.getAppiumDevice().getDevice().getUdid()
                    + "__" + testName + ".txt";
            logs.put("adbLogs", adbPath);
            logEntries.get().forEach(logEntry -> {
                log_file_writer.get().println(logEntry);
            });
            log_file_writer.get().close();
            ReportPortal.emitLog("ADB Logs", "DEBUG", new Date(), new File(adbPath));
        }
        return logs;
    }

    private boolean isNativeAndroid() {
        return AppiumDeviceManager.getMobilePlatform().equals(MobilePlatform.ANDROID)
            && AppiumDriverManager.getDriver().getCapabilities()
            .getCapability("browserName") == null;
    }

    private void stopViewRecording(ITestResult result, String className)
            throws IOException, InterruptedException {
        if ("true".equalsIgnoreCase(System.getenv("VIDEO_LOGS"))) {
            IScreenRecord videoRecording = AppiumScreenRecordFactory.recordScreen();
            videoRecording.stopVideoRecording(className, result.getMethod()
                    .getMethodName(), result.getMethod().getMethodName());
        }
        deleteSuccessVideos(result, className);
    }

    private void deleteSuccessVideos(ITestResult result, String className) {
        if (result.isSuccess()
                && (null != System.getenv("KEEP_ALL_VIDEOS"))
                && !(System.getenv("KEEP_ALL_VIDEOS").equalsIgnoreCase("true"))) {
            File videoFile = new File(System.getProperty("user.dir")
                    + FileLocations.ANDROID_SCREENSHOTS_DIRECTORY
                    + AppiumDeviceManager.getAppiumDevice().getDevice().getUdid() + "/"
                    + className + "/" + result.getMethod().getMethodName()
                    + "/" + result.getMethod().getMethodName() + ".mp4");
            if (videoFile.exists()) {
                videoFile.delete();
            }
        }
    }

    private void handleTestFailure(ITestResult result, String className,
                                   String deviceModel) {
        if (result.getStatus() == ITestResult.FAILURE) {
            String screenShotNameWithTimeStamp = screenShotManager
                    .captureScreenShot(result.getStatus(),
                            result.getInstance().getClass().getSimpleName(),
                            result.getMethod().getMethodName(),
                            result.getMethod().getMethodName(), deviceModel);

            if (AppiumDeviceManager.getMobilePlatform().equals(MobilePlatform.ANDROID)) {
                String imagePath = System.getProperty("user.dir")
                        + FileLocations.ANDROID_SCREENSHOTS_DIRECTORY
                        + AppiumDeviceManager.getAppiumDevice().getDevice().getUdid()
                        + "/" + className + "/" + result.getMethod()
                        .getMethodName() + "/" + screenShotNameWithTimeStamp
                        + "-" + result.getMethod().getMethodName() + "_failed.jpeg";
                ReportPortal.emitLog("Screenshots",
                        "ERROR", new Date(), new File(imagePath));
            }
            if (AppiumDeviceManager.getMobilePlatform().equals(MobilePlatform.IOS)) {
                String imagePath = System.getProperty("user.dir")
                        + FileLocations.IOS_SCREENSHOTS_DIRECTORY
                        + AppiumDeviceManager.getAppiumDevice().getDevice().getUdid()
                        + "/" + className + "/" + result.getMethod()
                        .getMethodName() + "/" + screenShotNameWithTimeStamp
                        + "-" + result.getMethod().getMethodName() + "_failed.jpeg";
                ReportPortal.emitLog("Screenshots",
                        "ERROR", new Date(), new File(imagePath));
            }
        }
    }


}
