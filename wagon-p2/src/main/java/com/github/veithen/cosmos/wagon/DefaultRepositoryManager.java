package com.github.veithen.cosmos.wagon;

import java.io.File;
import java.net.URI;
import java.util.Hashtable;

import org.apache.maven.artifact.manager.WagonManager;
import org.codehaus.plexus.logging.LogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Disposable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Initializable;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.eclipse.core.net.proxy.IProxyService;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepository;
import org.eclipse.equinox.p2.repository.artifact.IArtifactRepositoryManager;
import org.osgi.framework.Constants;

import com.github.veithen.cosmos.osgi.runtime.Configuration;
import com.github.veithen.cosmos.osgi.runtime.Runtime;
import com.github.veithen.cosmos.osgi.runtime.logging.plexus.PlexusLogger;
import com.github.veithen.cosmos.p2.P2Initializer;
import com.github.veithen.cosmos.p2.SystemOutProgressMonitor;

/**
 * @plexus.component role="com.github.veithen.cosmos.wagon.RepositoryManager"
 */
public class DefaultRepositoryManager implements RepositoryManager, Initializable, Disposable, LogEnabled {
    private IArtifactRepositoryManager repoman;
    
    /**
     * @plexus.requirement
     */
    private WagonManager wagonManager;
    
    private Logger logger;
    
    @Override
    public void enableLogging(Logger logger) {
        this.logger = logger;
    }

    public void initialize() throws InitializationException {
        try {
            // TODO: we need a hack here because the test case instantiates DefaultRepositoryManager directly; instead we should create a Plexus container and look up the component
            Runtime runtime = Runtime.getInstance(Configuration.newDefault().logger(logger == null ? null : new PlexusLogger(logger)).initializer(new P2Initializer(new File("target/p2-data"))).build());
            
            // TODO: wagonManager is only null in unit tests; find a way to inject a mock instance
            if (wagonManager != null) {
                System.out.println("Setting up proxy configuration");
                Hashtable<String,Object> properties = new Hashtable<String,Object>();
                properties.put(Constants.SERVICE_RANKING, Integer.valueOf(1));
                runtime.registerService(null, new String[] { IProxyService.class.getName() }, new ProxyServiceAdapter(wagonManager), properties);
            }
            
            IProvisioningAgent agent = runtime.getService(IProvisioningAgent.class);
            repoman = (IArtifactRepositoryManager)agent.getService(IArtifactRepositoryManager.SERVICE_NAME);
        } catch (Exception ex) {
            throw new InitializationException("Failed to initialize P2", ex);
        }
    }

    public IArtifactRepository loadRepository(URI uri) throws ProvisionException {
        System.out.println("Loading repository " + uri);
        return repoman.loadRepository(uri, new SystemOutProgressMonitor());
    }

    public void dispose() {
        
    }
}
