package com.github.veithen.cosmos.osgi.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleException;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.Filter;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.log.LogService;
import org.osgi.util.xml.XMLParserActivator;

import com.github.veithen.cosmos.osgi.runtime.logging.Logger;

public final class Runtime {
    private static Runtime instance;

    private final Configuration config;
    private final Logger logger;
    private final Properties properties = new Properties();
    private final Map<String,BundleImpl> bundlesBySymbolicName = new HashMap<String,BundleImpl>();
    private final Map<Long,BundleImpl> bundlesById = new HashMap<Long,BundleImpl>();
    private final List<BundleListener> bundleListeners = new LinkedList<BundleListener>();
    private final List<ServiceListenerSpec> serviceListeners = new LinkedList<ServiceListenerSpec>();
    private final List<Service> services = new LinkedList<Service>();
    private long nextServiceId = 1;
    private final File dataRoot;
    
    /**
     * Maps exported packages to their corresponding bundles.
     */
    private final Map<String,BundleImpl> packageMap = new HashMap<String,BundleImpl>();

    private Runtime(Configuration config) throws CosmosException, BundleException {
        this.config = config;
        logger = config.getLogger();
        // TODO: make this configurable
        dataRoot = new File("target/osgi");
        Enumeration<URL> e;
        try {
            e = Runtime.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
        } catch (IOException ex) {
            throw new CosmosException("Failed to load manifests", ex);
        }
        long bundleId = 1;
        while (e.hasMoreElements()) {
            URL url = e.nextElement();
            Manifest manifest;
            try {
                InputStream in = url.openStream();
                try {
                    manifest = new Manifest(in);
                } finally {
                    in.close();
                }
            } catch (IOException ex) {
                throw new CosmosException("Failed to read " + url, ex);
            }
            Attributes attrs = manifest.getMainAttributes();
            String symbolicName = attrs.getValue("Bundle-SymbolicName");
            if (symbolicName == null) {
                continue;
            }
            // Remove the "singleton" attribute
            int idx = symbolicName.indexOf(';');
            if (idx != -1) {
                symbolicName = symbolicName.substring(0, idx);
            }
            URL rootUrl;
            try {
                rootUrl = new URL(url, "..");
            } catch (MalformedURLException ex) {
                throw new CosmosException("Unexpected exception", ex);
            }
            // There cannot be any bundle listeners yet, so no need to call BundleListeners
            long id = bundleId++;
            BundleImpl bundle = new BundleImpl(this, id, symbolicName, attrs, rootUrl, new File(dataRoot, symbolicName));
            bundlesBySymbolicName.put(symbolicName, bundle);
            bundlesById.put(id, bundle);
            String exportPackage = attrs.getValue("Export-Package");
            if (exportPackage != null) {
                Element[] elements;
                try {
                    elements = Element.parseHeaderValue(exportPackage);
                } catch (ParseException ex) {
                    throw new BundleException("Unable to parse Export-Package header", BundleException.MANIFEST_ERROR, ex);
                }
                for (Element element : elements) {
                    // TODO: what if the same package is exported by multiple bundles??
                    packageMap.put(element.getValue(), bundle);
                }
            }
        }
        registerSAXParserFactory();
        registerDocumentBuilderFactory();
        registerService(null, new String[] { LogService.class.getName() }, new LogServiceAdapter(logger), null);
        RuntimeInitializer initializer = config.getInitializer();
        if (initializer != null) {
            initializer.initializeRuntime(this);
        }
    }

    private void registerSAXParserFactory() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        Hashtable<String,Object> props = new Hashtable<String,Object>();
        new XMLParserActivator().setSAXProperties(factory, props);
        registerService(null, new String[] { SAXParserFactory.class.getName() }, factory, props);
    }
    
    private void registerDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Hashtable<String,Object> props = new Hashtable<String,Object>();
        new XMLParserActivator().setDOMProperties(factory, props);
        registerService(null, new String[] { DocumentBuilderFactory.class.getName() }, factory, props);
    }
    
    public static synchronized Runtime getInstance(Configuration config) throws CosmosException, BundleException {
        if (instance == null) {
            instance = new Runtime(config);
        } else if (instance.config != config) {
            throw new IllegalStateException("Runtime already initialized with different configuration");
        }
        return instance;
    }
    
    public Logger getLogger() {
        return logger;
    }
    
    Configuration getConfig() {
        return config;
    }

    Bundle[] getBundles() {
        Collection<BundleImpl> c = bundlesBySymbolicName.values();
        return c.toArray(new Bundle[c.size()]);
    }
    
    public Bundle getBundle(String symbolicName) {
        return bundlesBySymbolicName.get(symbolicName);
    }
    
    BundleImpl getBundleByPackage(String pkg) {
        return packageMap.get(pkg);
    }
    
    Bundle getBundle(long id) {
        return bundlesById.get(id);
    }
    
    String getProperty(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            System.getProperty(key);
        }
        if (value == null && logger.isDebugEnabled()) {
            logger.debug("No value for property " + key);
        }
        return value;
    }
    
    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }
    
    void addBundleListener(BundleListener listener) {
        bundleListeners.add(listener);
    }

    void addServiceListener(BundleImpl bundle, ServiceListener listener, Filter filter) {
        if (logger.isDebugEnabled()) {
            logger.debug("Bundle " + bundle.getSymbolicName() + " starts listening for services with filter " + filter);
        }
        serviceListeners.add(new ServiceListenerSpec(listener, filter));
    }

    void removeServiceListener(ServiceListener listener) {
        for (Iterator<ServiceListenerSpec> it = serviceListeners.iterator(); it.hasNext(); ) {
            if (it.next().getListener() == listener) {
                it.remove();
            }
        }
    }

    public <T> ServiceRegistration<T> registerService(String[] classes, T serviceObject, Dictionary<String,?> properties) {
        return registerService((Bundle)null, classes, serviceObject, properties);
    }
    
    public <T> ServiceRegistration<T> registerService(Bundle bundle, String[] classes, T serviceObject, Dictionary<String,?> properties) {
        return (ServiceRegistration<T>)registerService((BundleImpl)bundle, classes, serviceObject, properties);
    }
    
    Service registerService(BundleImpl bundle, String[] classes, Object serviceObject, Dictionary<String,?> properties) {
        long serviceId = nextServiceId++;
        if (logger.isDebugEnabled()) {
            logger.debug("Registering service " + serviceObject.getClass().getName() + " with interfaces " + Arrays.asList(classes) + " and properties " + properties + "; id is " + serviceId);
        }
        Hashtable<String,Object> actualProperties = new Hashtable<String,Object>();
        if (properties != null) {
            for (Enumeration<String> keys = properties.keys(); keys.hasMoreElements(); ) {
                String key = keys.nextElement();
                actualProperties.put(key, properties.get(key));
            }
        }
        actualProperties.put(Constants.SERVICE_ID, serviceId);
        Service service = new Service(logger, bundle, classes, serviceObject, actualProperties);
        services.add(service);
        for (ServiceListenerSpec listener : serviceListeners) {
            if (service.matches(null, listener.getFilter())) {
                listener.getListener().serviceChanged(new ServiceEvent(ServiceEvent.REGISTERED, service));
            }
        }
        return service;
    }

    ServiceReference<?>[] getServiceReferences(String clazz, Filter filter) {
        List<ServiceReference<?>> references = new ArrayList<ServiceReference<?>>();
        for (Service service : services) {
            if (service.matches(clazz, filter)) {
                references.add(service);
            }
        }
        return references.toArray(new ServiceReference<?>[references.size()]);
    }

    ServiceReference<?> getServiceReference(String clazz, Filter filter) {
        List<ServiceReference<?>> references = new ArrayList<ServiceReference<?>>();
        for (Service service : services) {
            if (service.matches(clazz, filter)) {
                return service;
            }
        }
        return null;
    }

    public <T> T getService(Class<T> clazz) {
        ServiceReference<?> ref = getServiceReference(clazz.getName(), null);
        // TODO: need a system/framework bundle here
        return ref == null ? null : clazz.cast(((Service)ref).getService(null));
    }
    
    void fireBundleEvent(BundleImpl bundleImpl, int type) {
        BundleEvent event = new BundleEvent(type, bundleImpl);
        for (BundleListener listener : bundleListeners) {
            listener.bundleChanged(event);
        }
    }
}
