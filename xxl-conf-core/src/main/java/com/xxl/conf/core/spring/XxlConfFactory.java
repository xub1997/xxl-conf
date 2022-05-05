package com.xxl.conf.core.spring;

import com.xxl.conf.core.XxlConfClient;
import com.xxl.conf.core.annotation.XxlConf;
import com.xxl.conf.core.exception.XxlConfException;
import com.xxl.conf.core.factory.XxlConfBaseFactory;
import com.xxl.conf.core.listener.impl.BeanRefreshXxlConfListener;
import com.xxl.conf.core.util.FieldReflectionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.*;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.util.ReflectionUtils;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.util.Objects;

/**
 * XxlConf Factory
 *
 * @author xuxueli 2015-9-12 19:42:49
 */
public class XxlConfFactory extends InstantiationAwareBeanPostProcessorAdapter
        implements InitializingBean, DisposableBean, BeanNameAware, BeanFactoryAware {

    private static Logger logger = LoggerFactory.getLogger(XxlConfFactory.class);


    // ---------------------- env config ----------------------

    private String envprop;        // like "xxl-conf.properties" or "file:/data/webapps/xxl-conf.properties", include the following env config

    private String adminAddress;
    private String env;
    private String accessToken;
    private String mirrorfile;

    public void setAdminAddress(String adminAddress) {
        this.adminAddress = adminAddress;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setMirrorfile(String mirrorfile) {
        this.mirrorfile = mirrorfile;
    }

    // ---------------------- init/destroy ----------------------

    /**
     * 初始化bean的时候执行XxlConfBaseFactory的init方法
     * //初始化远程调用工具
     * XxlConfRemoteConf.init(adminAddress, env, accessToken);	// init remote util
     * //初始化镜像工具？？？
     * XxlConfMirrorConf.init(mirrorfile);			// init mirror util
     * //初始化缓存+线程，循环刷新+监控
     * XxlConfLocalCacheConf.init();				// init cache + thread, cycle refresh + monitor
     * <p>
     * //添加监听器，监听配置变化
     * XxlConfListenerFactory.addListener(null, new BeanRefreshXxlConfListener());    // listener all key change
     */
    @Override
    public void afterPropertiesSet() {
        XxlConfBaseFactory.init(adminAddress, env, accessToken, mirrorfile);
    }

    /**
     * 调用XxlConfBaseFactory的destroy方法（XxlConfLocalCacheConf.destroy）
     * 销毁XxlConfLocalCacheConf的refreshThread守护线程
     */
    @Override
    public void destroy() {
        XxlConfBaseFactory.destroy();
    }


    // ---------------------- post process / xml、annotation ----------------------

    /**
     * 这个方法在bean实例化之前被调用，返回的对象可能是代替了目标对象的代理对象，有效的阻止了目标bean默认的实例化。
     * 也就是说，如果该方法返回的是non-null对象 ，这个bean的创建过程就会被短路，
     * 就不会执行postProcessAfterInitialization的方法和postProcessPropertyValues方法; 相反的如果方法返回值为null,则会继续默认的bean的实例化过程
     *
     * @param bean
     * @param beanName
     * @return
     * @throws BeansException
     */
    @Override
    public boolean postProcessAfterInstantiation(final Object bean, final String beanName) throws BeansException {


        // 1、Annotation('@XxlConf')：resolves conf + watch
        if (!beanName.equals(this.beanName)) {

            ReflectionUtils.doWithFields(bean.getClass(), new ReflectionUtils.FieldCallback() {
                @Override
                public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
                    //加入属性带@XxlConf注解
                    if (field.isAnnotationPresent(XxlConf.class)) {
                        String propertyName = field.getName();
                        XxlConf xxlConf = field.getAnnotation(XxlConf.class);

                        String confKey = xxlConf.value();
                        String confValue = XxlConfClient.get(confKey, xxlConf.defaultValue());


                        // resolves placeholders 解析占位符@XxlConf
                        BeanRefreshXxlConfListener.BeanField beanField = new BeanRefreshXxlConfListener.BeanField(beanName, propertyName);
                        refreshBeanField(beanField, confValue, bean);

                        // watch 当值更改时，是否需要回调刷新。
                        if (xxlConf.callback()) {
                            BeanRefreshXxlConfListener.addBeanField(confKey, beanField);
                        }

                    }
                }
            });
        }

        return super.postProcessAfterInstantiation(bean, beanName);
    }

    @Override
    public PropertyValues postProcessPropertyValues(PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) throws BeansException {

        // 2、XML('$XxlConf{...}')：resolves placeholders + watch
        if (!beanName.equals(this.beanName)) {

            PropertyValue[] pvArray = pvs.getPropertyValues();
            for (PropertyValue pv : pvArray) {
                if (pv.getValue() instanceof TypedStringValue) {
                    String propertyName = pv.getName();
                    String typeStringVal = ((TypedStringValue) pv.getValue()).getValue();
                    if (xmlKeyValid(typeStringVal)) {

                        // object + property 处理键值
                        String confKey = xmlKeyParse(typeStringVal);
                        String confValue = XxlConfClient.get(confKey, "");

                        // resolves placeholders 解析占位符@XxlConf{}
                        BeanRefreshXxlConfListener.BeanField beanField = new BeanRefreshXxlConfListener.BeanField(beanName, propertyName);
                        //refreshBeanField(beanField, confValue, bean);

                        Class propClass = String.class;
                        for (PropertyDescriptor item : pds) {
                            if (beanField.getProperty().equals(item.getName())) {
                                propClass = item.getPropertyType();
                            }
                        }
                        Object valueObj = FieldReflectionUtil.parseValue(propClass, confValue);
                        pv.setConvertedValue(valueObj);

                        // watch
                        BeanRefreshXxlConfListener.addBeanField(confKey, beanField);

                    }
                }
            }

        }

        return super.postProcessPropertyValues(pvs, pds, bean, beanName);
    }

    // ---------------------- refresh bean with xxl conf  ----------------------

    /**
     * 使用 xxl conf (fieldNames) 刷新 bean
     * refresh bean with xxl conf (fieldNames)
     */
    public static void refreshBeanField(final BeanRefreshXxlConfListener.BeanField beanField, final String value, Object bean) {
        if (bean == null) {
            // 已优化：启动时禁止实用，getBean 会导致Bean提前初始化，风险较大；
            bean = XxlConfFactory.beanFactory.getBean(beanField.getBeanName());
        }
        if (bean == null) {
            return;
        }

        //BeanWrapper 是 Spring 的低级 JavaBeans 基础结构的中央接口，
        // 相当于一个代理器， 提供用于分析和操作标准 JavaBean 的操作：
        // 获得和设置属性值（单独或批量），获取属性描述符以及查询属性的可读性/可写性的能力。
        BeanWrapper beanWrapper = new BeanWrapperImpl(bean);

        // property descriptor(属性描述器)
        PropertyDescriptor propertyDescriptor = null;
        PropertyDescriptor[] propertyDescriptors = beanWrapper.getPropertyDescriptors();
        if (propertyDescriptors != null && propertyDescriptors.length > 0) {
            for (PropertyDescriptor item : propertyDescriptors) {
                if (beanField.getProperty().equals(item.getName())) {
                    propertyDescriptor = item;
                }
            }
        }

        // refresh field: set or field 刷新属性值，假如存在set方法则直接使用beanWrapper.setPropertyValue处理
        if (propertyDescriptor != null && propertyDescriptor.getWriteMethod() != null) {
            beanWrapper.setPropertyValue(beanField.getProperty(), value);    // support mult data types
            logger.info(">>>>>>>>>>> xxl-conf, refreshBeanField[set] success, {}#{}:{}",
                    beanField.getBeanName(), beanField.getProperty(), value);
        } else {

            //假如不存在set方法，则使用反射获取属性的类型，将属性值处理成要赋值的类型，通过反射刷新属性值
            final Object finalBean = bean;
            ReflectionUtils.doWithFields(bean.getClass(), new ReflectionUtils.FieldCallback() {
                @Override
                public void doWith(Field fieldItem) throws IllegalArgumentException, IllegalAccessException {
                    if (beanField.getProperty().equals(fieldItem.getName())) {
                        try {
                            Object valueObj = FieldReflectionUtil.parseValue(fieldItem.getType(), value);

                            fieldItem.setAccessible(true);
                            fieldItem.set(finalBean, valueObj);        // support mult data types

                            logger.info(">>>>>>>>>>> xxl-conf, refreshBeanField[field] success, {}#{}:{}",
                                    beanField.getBeanName(), beanField.getProperty(), value);
                        } catch (IllegalAccessException e) {
                            throw new XxlConfException(e);
                        }
                    }
                }
            });

			/*Field[] beanFields = bean.getClass().getDeclaredFields();
			if (beanFields!=null && beanFields.length>0) {
				for (Field fieldItem: beanFields) {
					if (beanField.getProperty().equals(fieldItem.getName())) {
						try {
							Object valueObj = FieldReflectionUtil.parseValue(fieldItem.getType(), value);

							fieldItem.setAccessible(true);
							fieldItem.set(bean, valueObj);		// support mult data types

							logger.info(">>>>>>>>>>> xxl-conf, refreshBeanField[field] success, {}#{}:{}",
									beanField.getBeanName(), beanField.getProperty(), value);
						} catch (IllegalAccessException e) {
							throw new XxlConfException(e);
						}
					}
				}
			}*/
        }

    }


    // ---------------------- util ----------------------

    /**
     * register beanDefinition If Not Exists
     *
     * @param registry
     * @param beanClass
     * @param beanName
     * @return
     */
    public static boolean registerBeanDefinitionIfNotExists(BeanDefinitionRegistry registry, Class<?> beanClass, String beanName) {

        // default bean name
        if (beanName == null) {
            beanName = beanClass.getName();
        }

        if (registry.containsBeanDefinition(beanName)) {    // avoid beanName repeat
            return false;
        }

        String[] beanNameArr = registry.getBeanDefinitionNames();
        for (String beanNameItem : beanNameArr) {
            BeanDefinition beanDefinition = registry.getBeanDefinition(beanNameItem);
            if (Objects.equals(beanDefinition.getBeanClassName(), beanClass.getName())) {    // avoid className repeat
                return false;
            }
        }

        BeanDefinition annotationProcessor = BeanDefinitionBuilder.genericBeanDefinition(beanClass).getBeanDefinition();
        registry.registerBeanDefinition(beanName, annotationProcessor);
        return true;
    }


    private static final String placeholderPrefix = "$XxlConf{";
    private static final String placeholderSuffix = "}";

    /**
     * valid xml
     *
     * @param originKey
     * @return
     */
    private static boolean xmlKeyValid(String originKey) {
        boolean start = originKey.startsWith(placeholderPrefix);
        boolean end = originKey.endsWith(placeholderSuffix);
        if (start && end) {
            return true;
        }
        return false;
    }

    /**
     * 解析xml的属性键
     * parse xml
     *
     * @param originKey
     * @return
     */
    private static String xmlKeyParse(String originKey) {
        if (xmlKeyValid(originKey)) {
            // replace by xxl-conf 获取真正的属性键
            String key = originKey.substring(placeholderPrefix.length(), originKey.length() - placeholderSuffix.length());
            return key;
        }
        return null;
    }


    // ---------------------- other ----------------------

    private String beanName;

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    private static BeanFactory beanFactory;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

}
