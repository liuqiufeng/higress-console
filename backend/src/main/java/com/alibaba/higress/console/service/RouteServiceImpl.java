package com.alibaba.higress.console.service;

import com.alibaba.higress.console.controller.dto.CommonPageQuery;
import com.alibaba.higress.console.controller.dto.PaginatedResult;
import com.alibaba.higress.console.controller.dto.Route;
import com.alibaba.higress.console.controller.exception.AlreadyExistedException;
import com.alibaba.higress.console.controller.exception.BusinessException;
import com.alibaba.higress.console.service.kubernetes.KubernetesClientService;
import com.alibaba.higress.console.service.kubernetes.KubernetesModelConverter;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Ingress;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

@Service
public class RouteServiceImpl implements RouteService {

    private KubernetesClientService kubernetesClientService;
    private KubernetesModelConverter kubernetesModelConverter;

    @Resource
    public void setKubernetesClientService(KubernetesClientService kubernetesClientService) {
        this.kubernetesClientService = kubernetesClientService;
    }

    @Resource
    public void setKubernetesModelConverter(KubernetesModelConverter kubernetesModelConverter) {
        this.kubernetesModelConverter = kubernetesModelConverter;
    }

    @Override
    public PaginatedResult<Route> list(CommonPageQuery query) {
        List<V1Ingress> ingresses = kubernetesClientService.listIngress();
        if (CollectionUtils.isEmpty(ingresses)) {
            return PaginatedResult.createFromFullList(Collections.emptyList(), query);
        }
        List<V1Ingress> supportedIngresses = ingresses.stream().filter(kubernetesModelConverter::isIngressSupported).toList();
        return PaginatedResult.createFromFullList(supportedIngresses, query, kubernetesModelConverter::ingress2Route);
    }

    @Override
    public Route query(String routeName) {
        V1Ingress ingress = kubernetesClientService.readIngress(routeName);
        return ingress != null ? kubernetesModelConverter.ingress2Route(ingress) : null;
    }

    @Override
    public Route add(Route route) {
        V1Ingress ingress = kubernetesModelConverter.route2Ingress(route);
        V1Ingress newIngress;
        try {
            newIngress = kubernetesClientService.createIngress(ingress);
        } catch (ApiException e) {
            if (e.getCode() == HttpStatus.CONFLICT.value()) {
                throw new AlreadyExistedException();
            }
            throw new BusinessException("Error occurs when updating the ingress generated by route with name: "
                    + route.getName(), e);
        }
        return kubernetesModelConverter.ingress2Route(newIngress);
    }

    @Override
    public Route update(Route route) {
        V1Ingress ingress = kubernetesModelConverter.route2Ingress(route);
        V1Ingress newIngress;
        try {
            newIngress = kubernetesClientService.replaceIngress(ingress);
        } catch (ApiException e) {
            throw new BusinessException("Error occurs when updating the ingress generated by route with name: "
                    + route.getName(), e);
        }
        return kubernetesModelConverter.ingress2Route(newIngress);
    }

    @Override
    public void delete(String name) {
        try {
            kubernetesClientService.deleteIngress(name);
        } catch (ApiException e) {
            throw new BusinessException("Error occurs when deleting ingress with name: " + name, e);
        }
    }
}
