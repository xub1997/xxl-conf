package com.xxl.conf.core.listener;

import com.xxl.conf.core.XxlConfClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * xxl conf listener 配置监听器工厂
 *
 * @author xuxueli 2018-02-04 01:27:30
 */
public class XxlConfListenerFactory {
    private static Logger logger = LoggerFactory.getLogger(XxlConfListenerFactory.class);

    /**
     * xxl conf listener repository
     */
    //监听具体的配置值的监听器
    private static ConcurrentHashMap<String, List<XxlConfListener>> keyListenerRepository = new ConcurrentHashMap<>();
    //没指定具体的配置值的监听器（处理所有的配置值）
    private static List<XxlConfListener> noKeyConfListener = Collections.synchronizedList(new ArrayList<XxlConfListener>());

    /**
     * add listener and first invoke + watch
     *
     * @param key   empty will listener all key
     * @param xxlConfListener
     * @return
     */
    public static boolean addListener(String key, XxlConfListener xxlConfListener){
        if (xxlConfListener == null) {
            return false;
        }
        if (key==null || key.trim().length()==0) {
            // listene all key used
            noKeyConfListener.add(xxlConfListener);
            return true;
        } else {

            // first use, invoke and watch this key
            try {
                String value = XxlConfClient.get(key);
                xxlConfListener.onChange(key, value);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }

            // listene this key
            List<XxlConfListener> listeners = keyListenerRepository.get(key);
            if (listeners == null) {
                listeners = new ArrayList<>();
                keyListenerRepository.put(key, listeners);
            }
            listeners.add(xxlConfListener);
            return true;
        }
    }

    /**
     * 当配置发生变化时，调用监听器去处理（listener.onChange(key, value)）
     * invoke listener on xxl conf change
     *
     * @param key
     */
    public static void onChange(String key, String value){
        if (key==null || key.trim().length()==0) {
            return;
        }
        //具体键值的监听器
        List<XxlConfListener> keyListeners = keyListenerRepository.get(key);
        if (keyListeners!=null && keyListeners.size()>0) {
            for (XxlConfListener listener : keyListeners) {
                try {
                    listener.onChange(key, value);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        //所有键值发生变化都会结果的noKeyConfListener监听器列表（BeanRefreshXxlConfListener）
        if (noKeyConfListener.size() > 0) {
            for (XxlConfListener confListener: noKeyConfListener) {
                try {
                    confListener.onChange(key, value);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

}
