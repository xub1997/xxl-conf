package com.xxl.conf.core.core;

import com.xxl.conf.core.XxlConfClient;
import com.xxl.conf.core.listener.XxlConfListenerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * local cache conf
 *
 * @author xuxueli 2018-02-01 19:11:25
 */
public class XxlConfLocalCacheConf {
    private static Logger logger = LoggerFactory.getLogger(XxlConfClient.class);


    // ---------------------- init/destroy ----------------------

    private static ConcurrentHashMap<String, CacheNode> localCacheRepository = null;

    /**
     * 刷新线程
     */
    private static Thread refreshThread;
    private static boolean refreshThreadStop = false;

    public static void init() {

        localCacheRepository = new ConcurrentHashMap<String, CacheNode>();

        // preload: mirror or remote 预加载（镜像文件、远程调用获取的配置信息）
        Map<String, String> preConfData = new HashMap<>();

        //读取配置文件
        Map<String, String> mirrorConfData = XxlConfMirrorConf.readConfMirror();

        Map<String, String> remoteConfData = null;
        if (mirrorConfData != null && mirrorConfData.size() > 0) {
            //调用远程接口，把镜像文件读取的配置重新进行读取
            remoteConfData = XxlConfRemoteConf.find(mirrorConfData.keySet());
        }

        //把镜像文件及远程调用的配置作为预加载的数据
        if (mirrorConfData != null && mirrorConfData.size() > 0) {
            preConfData.putAll(mirrorConfData);
        }
        if (remoteConfData != null && remoteConfData.size() > 0) {
            preConfData.putAll(remoteConfData);
        }
        if (preConfData != null && preConfData.size() > 0) {
            for (String preKey : preConfData.keySet()) {
                //放进ConcurrentHashMap<String, CacheNode> localCacheRepository
                set(preKey, preConfData.get(preKey), SET_TYPE.PRELOAD);
            }
        }

        // refresh thread 启动一个刷新属性值的守护线程
        refreshThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!refreshThreadStop) {
                    try {
                        TimeUnit.SECONDS.sleep(3);
                        //刷新Cache And Mirror，带实时监控
                        refreshCacheAndMirror();
                    } catch (Exception e) {
                        if (!refreshThreadStop && !(e instanceof InterruptedException)) {
                            logger.error(">>>>>>>>>> xxl-conf, refresh thread error.");
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                logger.info(">>>>>>>>>> xxl-conf, refresh thread stoped.");
            }
        });
        refreshThread.setDaemon(true);
        refreshThread.start();

        logger.info(">>>>>>>>>> xxl-conf, XxlConfLocalCacheConf init success.");
    }

    public static void destroy() {
        if (refreshThread != null) {
            refreshThreadStop = true;
            refreshThread.interrupt();
        }
    }

    /**
     * 本地缓存节点
     * local cache node
     */
    public static class CacheNode implements Serializable {
        private static final long serialVersionUID = 42L;

        private String value;

        public CacheNode() {
        }

        public CacheNode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }


    // ---------------------- util ----------------------

    /**
     * 刷新Cache And Mirror，带实时监控
     * refresh Cache And Mirror, with real-time minitor
     */
    private static void refreshCacheAndMirror() {

        if (localCacheRepository.size() == 0) {
            return;
        }

        //调用远程接口监听本地文件是否生成并保存具体值(利用DeferredResult进行处理)
        XxlConfRemoteConf.monitor(localCacheRepository.keySet());

        // refresh cache: remote > cache 刷新缓存：远程>缓存
        Set<String> keySet = localCacheRepository.keySet();
        if (keySet.size() > 0) {

            //调用远程接口查询本地缓存的键值的最新值
            Map<String, String> remoteDataMap = XxlConfRemoteConf.find(keySet);


            if (remoteDataMap != null && remoteDataMap.size() > 0) {
                for (String remoteKey : remoteDataMap.keySet()) {
                    String remoteData = remoteDataMap.get(remoteKey);

                    //判断本地缓存的值与查询回来的值是否一致
                    CacheNode existNode = localCacheRepository.get(remoteKey);
                    if (existNode != null && existNode.getValue() != null && existNode.getValue().equals(remoteData)) {
                        logger.debug(">>>>>>>>>> xxl-conf: RELOAD unchange-pass [{}].", remoteKey);
                    } else {
                        //不一致，则进行更新
                        set(remoteKey, remoteData, SET_TYPE.RELOAD);
                    }

                }
            }

        }

        // refresh mirror: cache > mirror  刷新镜像：缓存>镜像 ？？？
        Map<String, String> mirrorConfData = new HashMap<>();
        for (String key : keySet) {
            CacheNode existNode = localCacheRepository.get(key);
            mirrorConfData.put(key, existNode.getValue() != null ? existNode.getValue() : "");
        }
        XxlConfMirrorConf.writeConfMirror(mirrorConfData);

        logger.info(">>>>>>>>>> xxl-conf, refreshCacheAndMirror success.");
    }


    // ---------------------- inner api ----------------------

    public enum SET_TYPE {
        SET,        // first use 第一次使用
        RELOAD,     // value updated 数据刷新
        PRELOAD     // pre hot 预加载
    }

    /**
     * set conf (invoke listener)
     *
     * @param key
     * @param value
     * @return
     */
    private static void set(String key, String value, SET_TYPE optType) {
        //预加载时只需把具体的属性值放进localCacheRepository，其他情况需要触发一定的操作
        localCacheRepository.put(key, new CacheNode(value));
        logger.info(">>>>>>>>>> xxl-conf: {}: [{}={}]", optType, key, value);

        // value updated, invoke listener 假如属性值更新，需要调用监听器去刷新属性值
        if (optType == SET_TYPE.RELOAD) {
            XxlConfListenerFactory.onChange(key, value);
        }

        // new conf, new monitor 假如第一次使用这个属性，触发中断？？？？
        if (optType == SET_TYPE.SET) {
            refreshThread.interrupt();
        }
    }

    /**
     * get conf
     *
     * @param key
     * @return
     */
    private static CacheNode get(String key) {
        if (localCacheRepository.containsKey(key)) {
            CacheNode cacheNode = localCacheRepository.get(key);
            return cacheNode;
        }
        return null;
    }

    /**
     * update conf  (only update exists key)  (invoke listener)
     *
     * @param key
     * @param value
     */
    /*private static void update(String key, String value) {
        if (localCacheRepository.containsKey(key)) {
            set(key, value, SET_TYPE.UPDATE );
        }
    }*/

    /**
     * remove conf
     *
     * @param key
     * @return
     */
    /*private static void remove(String key) {
        if (localCacheRepository.containsKey(key)) {
            localCacheRepository.remove(key);
        }
        logger.info(">>>>>>>>>> xxl-conf: REMOVE: [{}]", key);
    }*/


    // ---------------------- api ----------------------

    /**
     * get conf 获取配置
     *
     * @param key
     * @param defaultVal
     * @return
     */
    public static String get(String key, String defaultVal) {

        // level 1: local cache 从本地缓存读
        XxlConfLocalCacheConf.CacheNode cacheNode = XxlConfLocalCacheConf.get(key);
        if (cacheNode != null) {
            return cacheNode.getValue();
        }

        // level 2	(get-and-watch, add-local-cache) 从远程接口里面拿，并加到本地缓存里面
        String remoteData = null;
        try {
            remoteData = XxlConfRemoteConf.find(key);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }

        // support cache null value 支持null值（对本地缓存的对象做了一层包装：new CacheNode(value)）
        set(key, remoteData, SET_TYPE.SET);
        if (remoteData != null) {
            return remoteData;
        }

        //还是没有值，则返回默认值
        return defaultVal;
    }

}
