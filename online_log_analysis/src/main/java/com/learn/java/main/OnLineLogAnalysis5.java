package com.learn.java.main;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;
import redis.clients.jedis.Jedis;

import java.sql.Timestamp;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Created by jpwu on 2017/1/13.
 *
 * 主要使用spark streaming and spark sql来实现:
 * 1.从kafka0.10 cluster读取log
 * 2.计算 cdh 角色日志，其格式: 机器名称 服务名称 时间 日志级别 日志信息
 * a.每隔1秒统计机器，服务的出现的error次数

 2017-01-13 19:57:16,366 DEBUG org.apache.hadoop.security.Groups: Returning cached groups for 'hdfs'
 2017-01-13 19:57:16,366 DEBUG org.apache.hadoop.hdfs.server.namenode.NameNodeResourceChecker: Space available on volume 'null' is 1025976619008
 2017-01-13 19:57:16,367 INFO org.apache.hadoop.ipc.Server: Served: monitorHealth queueTime= 0 procesingTime= 0

 *
 *
 */
public class OnLineLogAnalysis5 {

    //定义滑动间隔为5秒,窗口时间为30秒，即为计算每5秒的过去30秒的数据
    private static  final Duration slide_interval= new Duration(5 * 1000);
    private static  final Duration window_length= new Duration(30*1000);


    private static final Pattern regexSpace = Pattern.compile(" ");
    static String hostname,servicename,linetimestamp,logtype,loginfo;


    static String[] spiltstr;
    static CDHRoleLog cdhRoleLog;
    static String sqlstr;
    static Timestamp recordTimestamp;
    static String key;
    static String value;
    static String host_service_logtype;
    public static void main(String[] args) {
        try {
            //1.使用 SparkSession,JavaSparkContext, JavaStreamingContext来定义 对象 jsc
           SparkSession spark=  new SparkSession.Builder().master("local[2]").appName("OnLineLogAnalysis1").getOrCreate();
           JavaSparkContext sc=new JavaSparkContext(spark.sparkContext());
           JavaStreamingContext jssc= new JavaStreamingContext(sc,slide_interval);

            //2.设置kafka的map参数
            Map<String,Object> kafkaParams = new HashMap<String,Object>();
            kafkaParams.put("bootstrap.servers","172.16.101.58:9092,172.16.101.59:9092,172.16.101.60:9092"); //定义kakfa 服务的地址
            kafkaParams.put("key.deserializer",StringDeserializer.class);//key的序列化类
            kafkaParams.put("value.deserializer",StringDeserializer.class);//value的序列化类
            kafkaParams.put("group.id","use_a_separate_group_id_for_each_stream");//制定consumer group
            kafkaParams.put("auto.offset.reset","latest");
            kafkaParams.put("enable.auto.commit",false);//是否自动确认offset

            //3.创建要从kafka去读取的topic的集合对象
            Collection<String> topics = Arrays.asList("logtopic");

            //4.输入流
            JavaInputDStream<ConsumerRecord<String,String>> lines= KafkaUtils.createDirectStream(
                    jssc,
                    LocationStrategies.PreferConsistent(),
                    ConsumerStrategies.<String,String> Subscribe(topics,kafkaParams));

            // A DStream of RDD's that contain parsed CDH Role Logs.
           JavaDStream<CDHRoleLog> cdhRoleLogDStream =
                   lines.map(new Function<ConsumerRecord<String, String>, CDHRoleLog>() {
                       @Override
                       public CDHRoleLog call(ConsumerRecord<String, String> logline) throws Exception {
                           if(logline.value().contains("INFO")==true || logline.value().contains("WARN")==true || logline.value().contains("ERROR")==true || logline.value().contains("DEBUG")==true){
                               //一个log的输出的第一行
                               spiltstr = logline.value().split(" "); //按空格分割
                               cdhRoleLog = new CDHRoleLog(
                                       spiltstr[0],
                                       spiltstr[1],
                                       spiltstr[2]+" "+spiltstr[3],
                                       spiltstr[4],
                                       logline.value().substring(spiltstr[0].length()+spiltstr[1].length()+spiltstr[2].length()+spiltstr[3].length()+spiltstr[4].length()+spiltstr[4].length()+1)
                               );

                           }else {
                               //一个log的输出的非第一行
                               cdhRoleLog=null;
                           }
                           return  cdhRoleLog;
                       }
                   });

          //过滤无效的RDD
           JavaDStream<CDHRoleLog>  cdhRoleLogFilterDStream= cdhRoleLogDStream.filter(new Function<CDHRoleLog, Boolean>() {
                @Override
                public Boolean call(CDHRoleLog v1) throws Exception {
                    return v1!=null?true:false;
                }
            });

            // Splits the cdhRoleLogFilterDStream into a dstream of time windowed rdd's.
            JavaDStream<CDHRoleLog> windowDStream =
                    cdhRoleLogFilterDStream.window(window_length, slide_interval);

            //使用foreachRDD
            windowDStream.foreachRDD(new VoidFunction<JavaRDD<CDHRoleLog>>() {
                @Override
                public void call(JavaRDD<CDHRoleLog> cdhRoleLogJavaRDD) throws Exception {

                    //判断rdd的数目
                    if (cdhRoleLogJavaRDD.count() == 0) {
                        System.out.println("No cdh role logs in this time interval");
                        return;
                    }

                    // 从RDD创建Dataset
                    Dataset<Row> cdhRoleLogDR=spark.createDataFrame(cdhRoleLogJavaRDD,CDHRoleLog.class);

                    //注册为临时表
                    cdhRoleLogDR.createOrReplaceTempView("cdhrolelogs");

                    //定义sql
                    sqlstr="SELECT hostName,serviceName,'INFO' logType,COUNT(logType) FROM cdhrolelogs where logType='INFO' GROUP BY hostName,serviceName " +
                            "union all " +
                            "SELECT hostName,serviceName,'DEBUG' logType,COUNT(logType) FROM cdhrolelogs where logType='DEBUG' GROUP BY hostName,serviceName " +
                            "union all " +
                            "SELECT hostName,serviceName,'WARN' logType,COUNT(logType) FROM cdhrolelogs where logType='WARN' GROUP BY hostName,serviceName " +
                            "union all " +
                            "SELECT hostName,serviceName,'ERROR' logType,COUNT(logType) FROM cdhrolelogs where logType='ERROR' GROUP BY hostName,serviceName ";

                    //计算结果为List<Row>
                    List<Row> logtypecount = spark.sql(sqlstr).collectAsList();

                    //时间
                    recordTimestamp=new Timestamp(System.currentTimeMillis());

                    //设置jedis对象
                    Jedis jedis = RedisUtil.getJedis();
                    jedis.select(0); //选择db0

                    //将 timestamp作为key
                    key=recordTimestamp.toString();
                    //循环处理
                    value="[";
                    for(Row rowlog:logtypecount){

                        host_service_logtype=rowlog.get(0)+"_"+rowlog.get(1)+"_"+rowlog.get(2);
                        value=value+" {\"host_service_logtype\": \""+host_service_logtype+"\"," +
                                      "\"count\":\""+String.valueOf(rowlog.getLong(3))+"\" },";

                    }
                    value=value.substring(0,value.length()-1)+"]"; //去除最后一个字符“,” 和 拼接 “]”

                    //打印
                    System.out.println(value);
                    //保存
                    jedis.set(key,value);

                    if(jedis.isConnected()){
                        RedisUtil.returnResource(jedis);


                    }

                }
            });

            jssc.start(); //启动流式计算
            jssc.awaitTermination(); //等待中断
            jssc.close(); //关闭

        }catch (Exception e){

            e.printStackTrace();

        }


    }


}
