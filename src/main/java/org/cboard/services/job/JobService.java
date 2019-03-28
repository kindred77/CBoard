package org.cboard.services.job;

import com.alibaba.fastjson.JSONObject;
import com.gargoylesoftware.htmlunit.*;
import com.gargoylesoftware.htmlunit.html.*;
import com.sun.webkit.WebPage;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.cboard.dao.JobDao;
import org.cboard.dto.ViewDashboardJob;
import org.cboard.pojo.DashboardJob;
import org.cboard.services.MailService;
import org.cboard.services.ServiceStatus;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.phantomjs.PhantomJSDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriverService;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Created by yfyuan on 2017/2/17.
 */
@Service
public class JobService implements InitializingBean {

    @Autowired
    private SchedulerFactoryBean schedulerFactoryBean;

    @Autowired
    private JobDao jobDao;

    @Value("${admin_user_id}")
    private String adminUserId;

    @Autowired
    private MailService mailService;

    private static Logger LOG = LoggerFactory.getLogger(JobService.class);

    public void configScheduler() {
        Scheduler scheduler = schedulerFactoryBean.getScheduler();

        try {
            scheduler.clear();
        } catch (SchedulerException e) {
            LOG.error("" , e);
        }
        List<DashboardJob> jobList = jobDao.getJobList(adminUserId);
        for (DashboardJob job : jobList) {
            try {
                long startTimeStamp = job.getStartDate().getTime();
                long endTimeStamp = job.getEndDate().getTime();
                if (endTimeStamp < System.currentTimeMillis()) {
                    // Skip expired job
                    continue;
                }
                JobDetail jobDetail = JobBuilder.newJob(getJobExecutor(job)).withIdentity(job.getId().toString()).build();
                CronTrigger trigger = TriggerBuilder.newTrigger()
                        .startAt(new Date().getTime() < startTimeStamp ? job.getStartDate() : new Date())
                        .withSchedule(CronScheduleBuilder.cronSchedule(job.getCronExp()))
                        .endAt(job.getEndDate())
                        .build();
                jobDetail.getJobDataMap().put("job", job);
                scheduler.scheduleJob(jobDetail, trigger);
            } catch (SchedulerException e) {
                LOG.error("{} Job id: {}", e.getMessage(), job.getId());
            } catch (Exception e) {
                LOG.error("" , e);
            }
        }
    }

    private Class<? extends Job> getJobExecutor(DashboardJob job) {
        switch (job.getJobType()) {
            case "mail":
                return MailJobExecutor.class;
        }
        return null;
    }

    protected void sendMail(DashboardJob job) {
        jobDao.updateLastExecTime(job.getId(), new Date());
        try {
            jobDao.updateStatus(job.getId(), ViewDashboardJob.STATUS_PROCESSING, "");
            mailService.sendDashboard(job);
            jobDao.updateStatus(job.getId(), ViewDashboardJob.STATUS_FINISH, "");
        } catch (Exception e) {
            LOG.error("" , e);
            jobDao.updateStatus(job.getId(), ViewDashboardJob.STATUS_FAIL, ExceptionUtils.getStackTrace(e));
        }
    }

    public ServiceStatus save(String userId, String json) {
        JSONObject jsonObject = JSONObject.parseObject(json);
        DashboardJob job = new DashboardJob();
        job.setUserId(userId);
        job.setName(jsonObject.getString("name"));
        job.setConfig(jsonObject.getString("config"));
        job.setCronExp(jsonObject.getString("cronExp"));
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            job.setStartDate(format.parse(jsonObject.getJSONObject("daterange").getString("startDate")));
            job.setEndDate(format.parse(jsonObject.getJSONObject("daterange").getString("endDate")));
        } catch (ParseException e) {
            LOG.error("" , e);
        }
        job.setJobType(jsonObject.getString("jobType"));
        jobDao.save(job);
        configScheduler();
        return new ServiceStatus(ServiceStatus.Status.Success, "success");
    }

    public ServiceStatus update(String userId, String json) {
        JSONObject jsonObject = JSONObject.parseObject(json);
        DashboardJob job = new DashboardJob();
        job.setId(jsonObject.getLong("id"));
        job.setName(jsonObject.getString("name"));
        job.setConfig(jsonObject.getString("config"));
        job.setCronExp(jsonObject.getString("cronExp"));
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            job.setStartDate(format.parse(jsonObject.getJSONObject("daterange").getString("startDate")));
            job.setEndDate(format.parse(jsonObject.getJSONObject("daterange").getString("endDate")));
        } catch (ParseException e) {
            LOG.error("" , e);
        }
        job.setJobType(jsonObject.getString("jobType"));
        jobDao.update(job);
        configScheduler();
        return new ServiceStatus(ServiceStatus.Status.Success, "success");
    }

    public ServiceStatus delete(String userId, Long id) {
        jobDao.delete(id);
        configScheduler();
        return new ServiceStatus(ServiceStatus.Status.Success, "success");
    }

    public ServiceStatus exec(String userId, Long id) {
        DashboardJob job = jobDao.getJob(id);
        new Thread(() ->
                sendMail(job)
        ).start();
        return new ServiceStatus(ServiceStatus.Status.Success, "success");
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        configScheduler();
    }

    public static void main(String args[]){

        //js function：getRouteInfo，入参为province
        String routeScript="function getRouteInfo(province){ \n" + // 参数不要带var。。不然后面执行方法的时候会报错。。
                "      if (province=='henan') " +
                "         return 'http://127.0.0.1/resweb';\n" +
                "      else  " +
                "         return '未找到对应的省份信息，province='+province;\n" +
                "}";

        String scriptResult ="";//脚本的执行结果

        ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");//1.得到脚本引擎
        try {
            //2.引擎读取 脚本字符串
            engine.eval(new StringReader(routeScript));
            //如果js存在文件里，举例
            // Resource aesJs = new ClassPathResource("js/aes.js");
            // this.engine.eval(new FileReader(aesJs.getFile()));

            //3.将引擎转换为Invocable，这样才可以掉用js的方法
            Invocable invocable = (Invocable) engine;

            //4.使用 invocable.invokeFunction掉用js脚本里的方法，第一個参数为方法名，后面的参数为被调用的js方法的入参
            scriptResult = (String) invocable.invokeFunction("getRouteInfo", "henan");

        }catch(Exception e){
            e.printStackTrace();
            System.out.println("Error executing script: "+ e.getMessage()+" script:["+routeScript+"]");
        }
        System.out.println(scriptResult.toString());
    }

//    public static void main(String[] args) throws InterruptedException, IOException {
//        //WebClient配置
//        final WebClient webClient  = new WebClient(BrowserVersion.CHROME);
//        WebClientOptions options = webClient.getOptions();
//        options.setCssEnabled(false);
//        options.setJavaScriptEnabled(true);
//        options.setRedirectEnabled(true);
//        //启动cookie管理
//        webClient.setCookieManager(new CookieManager());
//        webClient.setAjaxController(new NicelyResynchronizingAjaxController());
//        webClient.getOptions().setThrowExceptionOnScriptError(false);
//
//        final HtmlPage page1 = webClient.getPage("http://192.168.149.26:8088/metadata_war_exploded/admin/index");
//        HtmlForm form = (HtmlForm) page1.getElementsByTagName("form").get(0);
//
//        //手动输入用户名
//        final HtmlTextInput username = form.getInputByName("flaccount");
//        username.setValueAttribute("admin");
//
//        //手动输入密码
//        final HtmlPasswordInput password = form.getInputByName("flpassword");
//        password.setValueAttribute("admin");
//
//        //登录
//        final HtmlButtonInput button = form.getInputByName("enter");
//
//        final HtmlPage page2 = button.click();
//
//        //设置必要参数
//        DesiredCapabilities dcaps = new DesiredCapabilities();
//        //ssl证书支持
//        dcaps.setCapability("acceptSslCerts", true);
//        //截屏支持
//        dcaps.setCapability("takesScreenshot", true);
//        //css搜索支持
//        dcaps.setCapability("cssSelectorsEnabled", true);
//        //js支持
//        dcaps.setJavascriptEnabled(true);
//        //驱动支持（第二参数表明的是你的phantomjs引擎所在的路径）
//        dcaps.setCapability(PhantomJSDriverService.PHANTOMJS_EXECUTABLE_PATH_PROPERTY,
//                "/Users/liuwei/phantomjs-2.1.1-macosx/bin/phantomjs");
//        //创建无界面浏览器对象
//        PhantomJSDriver driver = new PhantomJSDriver(dcaps);
//
//        //设置隐性等待（作用于全局）
//        driver.manage().timeouts().implicitlyWait(1, TimeUnit.SECONDS);
//        long start = System.currentTimeMillis();
//        //打开页面
//        driver.get("http://192.168.149.26:8088/metadata_war_exploded/admin/index");
//        Thread.sleep(30 * 1000);
//        JavascriptExecutor js = driver;
//        for (int i = 0; i <1; i++) {
//            js.executeScript("window.scrollBy(0,1000)");
//            //睡眠10s等js加载完成
//            Thread.sleep(5 * 1000);
//        }
//        //指定了OutputType.FILE做为参数传递给getScreenshotAs()方法，其含义是将截取的屏幕以文件形式返回。
//        File srcFile = ((TakesScreenshot)driver).getScreenshotAs(OutputType.FILE);
//        Thread.sleep(3000);
//        //利用FileUtils工具类的copyFile()方法保存getScreenshotAs()返回的文件对象
//        FileUtils.copyFile(srcFile, new File("/Users/liuwei/Downloads/ab123c.png"));
//        System.out.println("耗时：" + (System.currentTimeMillis() - start) + " 毫秒");
//    }
}
