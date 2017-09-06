package org.ballerinalang.net.http;

import org.ballerinalang.connector.api.AnnAttrValue;
import org.ballerinalang.connector.api.Annotation;
import org.ballerinalang.connector.api.BallerinaConnectorException;
import org.ballerinalang.connector.api.BallerinaServerConnector;
import org.ballerinalang.connector.api.Registry;
import org.ballerinalang.connector.api.Resource;
import org.ballerinalang.connector.api.Service;
import org.ballerinalang.net.uri.DispatcherUtil;
import org.ballerinalang.net.uri.URITemplateException;
import org.ballerinalang.net.uri.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonMessage;
import org.wso2.carbon.messaging.exceptions.ServerConnectorException;
import org.wso2.carbon.transport.http.netty.contract.ServerConnector;

import java.io.PrintStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by rajith on 9/4/17.
 */
public class HttpServerConnector implements BallerinaServerConnector {
    private static final Logger log = LoggerFactory.getLogger(HttpServerConnector.class);

    private CopyOnWriteArrayList<String> sortedServiceURIs = new CopyOnWriteArrayList<>();

    private HttpRegistry httpRegistry;

    public HttpServerConnector() {
        httpRegistry = new HttpRegistry(this);
    }

    @Override
    public String getProtocolPackage() {
        return Constants.PROTOCOL_PACKAGE_HTTP;
    }

    @Override
    public void serviceRegistered(Service service) throws BallerinaConnectorException {
        HttpService httpService = new HttpService(service);
        HTTPServicesRegistry.getInstance().registerService(httpService);
        Map<String, List<String>> serviceCorsMap = CorsRegistry.getInstance().getServiceCors(httpService);
        for (Resource resource : httpService.getResources()) {
            Annotation rConfigAnnotation = resource.getAnnotation(Constants.HTTP_PACKAGE_PATH,
                    Constants.ANN_NAME_RESOURCE_CONFIG);
            String subPathAnnotationVal;
            if (rConfigAnnotation == null) {
                if (log.isDebugEnabled()) {
                    log.debug("resourceConfig not specified in the Resource, using default sub path");
                }
                subPathAnnotationVal = resource.getName();
            } else {
                AnnAttrValue pathAttrVal = rConfigAnnotation.getAnnAttrValue(Constants.ANN_RESOURCE_ATTR_PATH);
                if (pathAttrVal == null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Path not specified in the Resource, using default sub path");
                    }
                    subPathAnnotationVal = resource.getName();
                } else if (pathAttrVal.getStringValue().trim().isEmpty()) {
                    subPathAnnotationVal = Constants.DEFAULT_BASE_PATH;
                } else {
                    subPathAnnotationVal = pathAttrVal.getStringValue();
                }
            }

            try {
                httpService.getUriTemplate().parse(subPathAnnotationVal, resource);
            } catch (URITemplateException e) {
                throw new BallerinaConnectorException(e.getMessage());
            }
            CorsRegistry.getInstance().processResourceCors(resource, serviceCorsMap);
        }
        String basePath = DispatcherUtil.getServiceBasePath(httpService);
        sortedServiceURIs.add(basePath);
        sortedServiceURIs.sort((basePath1, basePath2) -> basePath2.length() - basePath1.length());
    }

    @Override
    public void serviceUnregistered(Service service) throws BallerinaConnectorException {
        HTTPServicesRegistry.getInstance().unregisterService(service);

        String basePath = DispatcherUtil.getServiceBasePath(service);
        sortedServiceURIs.remove(basePath);
    }

    @Override
    public void complete() throws BallerinaConnectorException {
        try {
            // Starting up HTTP Server connectors
            //TODO move this to a common location and use in both http and ws server connectors
            PrintStream outStream = System.out;
            List<ServerConnector> startedHTTPConnectors = HttpConnectionManager.getInstance()
                    .startPendingHTTPConnectors();
            startedHTTPConnectors.forEach(serverConnector -> outStream.println("ballerina: started " +
                    "server connector " + serverConnector));
        } catch (ServerConnectorException e) {
            throw new BallerinaConnectorException(e);
        }
    }

    @Override
    public Registry getRegistry() {
        return httpRegistry;
    }

    public HttpService findService(CarbonMessage cMsg) {

        try {
            String interfaceId = getInterface(cMsg);
            Map<String, HttpService> servicesOnInterface = HTTPServicesRegistry
                    .getInstance().getServicesInfoByInterface(interfaceId);
            if (servicesOnInterface == null) {
                throw new BallerinaConnectorException("No services found for interface : " + interfaceId);
            }
            String uriStr = (String) cMsg.getProperty(org.wso2.carbon.messaging.Constants.TO);
            //replace multiple slashes from single slash if exist in request path to enable
            // dispatchers when request path contains multiple slashes
            URI requestUri = URI.create(uriStr.replaceAll("//+", Constants.DEFAULT_BASE_PATH));
            if (requestUri == null) {
                throw new BallerinaConnectorException("uri not found in the message or found an invalid URI.");
            }

            // Most of the time we will find service from here
            String basePath = findTheMostSpecificBasePath(requestUri.getPath(), servicesOnInterface);
            HttpService service = servicesOnInterface.get(basePath);
            if (service == null) {
                cMsg.setProperty(Constants.HTTP_STATUS_CODE, 404);
                throw new BallerinaConnectorException("no matching service found for path : " + uriStr);
            }

            String subPath = URIUtil.getSubPath(requestUri.getPath(), basePath);
            cMsg.setProperty(Constants.BASE_PATH, basePath);
            cMsg.setProperty(Constants.SUB_PATH, subPath);
            cMsg.setProperty(Constants.QUERY_STR, requestUri.getQuery());
            //store query params comes with request as it is
            cMsg.setProperty(Constants.RAW_QUERY_STR, requestUri.getRawQuery());

            return service;
        } catch (Throwable e) {
            throw new BallerinaConnectorException(e.getMessage());
        }
    }


    protected String getInterface(CarbonMessage cMsg) {
        String interfaceId = (String) cMsg.getProperty(org.wso2.carbon.messaging.Constants.LISTENER_INTERFACE_ID);
        if (interfaceId == null) {
            if (log.isDebugEnabled()) {
                log.debug("Interface id not found on the message, hence using the default interface");
            }
            interfaceId = Constants.DEFAULT_INTERFACE;
        }

        return interfaceId;
    }

    private String findTheMostSpecificBasePath(String requestURIPath, Map<String, HttpService> services) {
        for (Object key : sortedServiceURIs) {
            if (requestURIPath.toLowerCase().contains(key.toString().toLowerCase())) {
                if (requestURIPath.length() > key.toString().length()) {
                    if (requestURIPath.charAt(key.toString().length()) == '/') {
                        return key.toString();
                    }
                } else {
                    return key.toString();
                }
            }
        }
        if (services.containsKey(Constants.DEFAULT_BASE_PATH)) {
            return Constants.DEFAULT_BASE_PATH;
        }
        return null;
    }
}
