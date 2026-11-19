/*
 * Licensed to GraphHopper GmbH under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * GraphHopper GmbH licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.
 */
package com.graphhopper.resources;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.gtfs.GHLocation;
import com.graphhopper.gtfs.PtRouter;
import com.graphhopper.gtfs.Request;
import com.graphhopper.http.GHRequestTransformer;
import com.graphhopper.http.ProfileResolver;
import com.graphhopper.jackson.MultiException;
import com.graphhopper.jackson.ResponsePathSerializer;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PMap;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.shapes.GHPoint;
import org.glassfish.hk2.api.ServiceLocator;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A compact, mode-oriented entry point. Existing /route and /route-pt endpoints
 * remain unchanged for backwards compatibility.
 */
@Path("route/plan")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UnifiedRouteResource {
    private final GraphHopperConfig config;
    private final GraphHopper graphHopper;
    private final ProfileResolver profileResolver;
    private final GHRequestTransformer requestTransformer;
    private final ServiceLocator serviceLocator;
    private final String osmDate;

    @Inject
    public UnifiedRouteResource(GraphHopperConfig config, GraphHopper graphHopper,
                                ProfileResolver profileResolver, GHRequestTransformer requestTransformer,
                                ServiceLocator serviceLocator) {
        this.config = config;
        this.graphHopper = graphHopper;
        this.profileResolver = profileResolver;
        this.requestTransformer = requestTransformer;
        this.serviceLocator = serviceLocator;
        this.osmDate = graphHopper.getProperties().getAll().get("datareader.data.date");
    }

    @POST
    public Response plan(RoutePlanRequest request) {
        validate(request);
        StopWatch watch = new StopWatch().start();
        ObjectNode nativeResult;
        if ("transit".equals(request.getMode())) {
            nativeResult = routeTransit(request);
        } else {
            nativeResult = routeRoad(request);
        }
        long took = Math.round(watch.stop().getMillis());
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("mode", request.getMode());
        response.put("took", took);
        response.set("result", nativeResult);
        return Response.ok(response).header("X-GH-Took", took).build();
    }

    private ObjectNode routeRoad(RoutePlanRequest plan) {
        String profile = plan.getOptions().getProfile();
        if (profile == null || profile.isBlank()) {
            profile = switch (plan.getMode()) {
                case "walking" -> "foot";
                case "cycling" -> "bike";
                case "driving" -> "car";
                default -> throw new IllegalArgumentException("Unsupported route mode: " + plan.getMode());
            };
        }

        GHRequest request = new GHRequest(
                new GHPoint(plan.getOrigin().getLatitude(), plan.getOrigin().getLongitude()),
                new GHPoint(plan.getDestination().getLatitude(), plan.getDestination().getLongitude()));
        request.setProfile(profile);
        request.setLocale(plan.getOptions().getLocale());
        if (plan.getOptions().getAlgorithm() != null)
            request.setAlgorithm(plan.getOptions().getAlgorithm());

        PMap hints = request.getHints();
        hints.putObject("instructions", plan.getOptions().isInstructions());
        hints.putObject("calc_points", plan.getOptions().isGeometry());
        hints.putObject("elevation", plan.getOptions().isElevation());
        hints.putObject("points_encoded", plan.getOptions().isPointsEncoded());
        request = requestTransformer.transformRequest(request);

        PMap resolverHints = new PMap(request.getHints());
        resolverHints.putObject("profile", request.getProfile());
        request.setProfile(profileResolver.resolveProfile(resolverHints));

        GHResponse ghResponse = graphHopper.route(request);
        if (ghResponse.hasErrors())
            throw new MultiException(ghResponse.getErrors());

        return ResponsePathSerializer.jsonObject(ghResponse,
                new ResponsePathSerializer.Info(config.getCopyrights(), 0, osmDate),
                plan.getOptions().isInstructions(), plan.getOptions().isGeometry(),
                plan.getOptions().isElevation(), plan.getOptions().isPointsEncoded(), 1e5);
    }

    private ObjectNode routeTransit(RoutePlanRequest plan) {
        PtRouter router = serviceLocator.getService(PtRouter.class);
        if (router == null)
            throw new IllegalStateException("Public transit is not enabled. Configure gtfs.file first.");

        Options options = plan.getOptions();
        if (options.getDepartureTime() == null || options.getDepartureTime().isBlank())
            throw new IllegalArgumentException("options.departureTime is required for transit mode");

        List<GHLocation> points = List.of(
                GHLocation.fromString(plan.getOrigin().getLatitude() + "," + plan.getOrigin().getLongitude()),
                GHLocation.fromString(plan.getDestination().getLatitude() + "," + plan.getDestination().getLongitude()));
        Request ptRequest = new Request(points, OffsetDateTime.parse(options.getDepartureTime()).toInstant());
        ptRequest.setArriveBy(options.isArriveBy());
        ptRequest.setLocale(Helper.getLocale(options.getLocale()));
        if (options.getLimitSolutions() != null)
            ptRequest.setLimitSolutions(options.getLimitSolutions());
        if (options.getLimitTripTime() != null)
            ptRequest.setLimitTripTime(Duration.parse(options.getLimitTripTime()));
        if (options.getLimitStreetTime() != null)
            ptRequest.setLimitStreetTime(Duration.parse(options.getLimitStreetTime()));
        if (options.getIgnoreTransfers() != null)
            ptRequest.setIgnoreTransfers(options.getIgnoreTransfers());
        if (options.getAccessProfile() != null)
            ptRequest.setAccessProfile(options.getAccessProfile());
        if (options.getEgressProfile() != null)
            ptRequest.setEgressProfile(options.getEgressProfile());
        ptRequest.setBetaAccessTime(options.getBetaAccessTime());
        ptRequest.setBetaEgressTime(options.getBetaEgressTime());

        GHResponse response = router.route(ptRequest);
        if (response.hasErrors())
            throw new MultiException(response.getErrors());
        return ResponsePathSerializer.jsonObject(response,
                new ResponsePathSerializer.Info(config.getCopyrights(), 0, null),
                true, true, false, false, -1);
    }

    private static void validate(RoutePlanRequest request) {
        if (request == null)
            throw new IllegalArgumentException("Request body is required");
        if (request.getMode() == null || !List.of("walking", "cycling", "driving", "transit").contains(request.getMode()))
            throw new IllegalArgumentException("mode must be one of: walking, cycling, driving, transit");
        validatePoint("origin", request.getOrigin());
        validatePoint("destination", request.getDestination());
        if (request.getOptions() == null)
            request.setOptions(new Options());
        if (request.getOptions().getLocale() == null)
            request.getOptions().setLocale("en");
    }

    private static void validatePoint(String name, Coordinate point) {
        if (point == null)
            throw new IllegalArgumentException(name + " is required");
        if (!Double.isFinite(point.getLongitude()) || point.getLongitude() < -180 || point.getLongitude() > 180)
            throw new IllegalArgumentException(name + ".longitude must be between -180 and 180");
        if (!Double.isFinite(point.getLatitude()) || point.getLatitude() < -90 || point.getLatitude() > 90)
            throw new IllegalArgumentException(name + ".latitude must be between -90 and 90");
    }

    public static class RoutePlanRequest {
        private String mode;
        private Coordinate origin;
        private Coordinate destination;
        private Options options = new Options();
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public Coordinate getOrigin() { return origin; }
        public void setOrigin(Coordinate origin) { this.origin = origin; }
        public Coordinate getDestination() { return destination; }
        public void setDestination(Coordinate destination) { this.destination = destination; }
        public Options getOptions() { return options; }
        public void setOptions(Options options) { this.options = options; }
    }

    public static class Coordinate {
        private double longitude;
        private double latitude;
        public double getLongitude() { return longitude; }
        public void setLongitude(double longitude) { this.longitude = longitude; }
        public double getLatitude() { return latitude; }
        public void setLatitude(double latitude) { this.latitude = latitude; }
    }

    public static class Options {
        private String profile;
        private String locale = "en";
        private String algorithm;
        private boolean instructions = true;
        private boolean geometry = true;
        private boolean elevation;
        private boolean pointsEncoded;
        private String departureTime;
        private boolean arriveBy;
        private Boolean ignoreTransfers;
        private Integer limitSolutions;
        private String limitTripTime;
        private String limitStreetTime;
        private String accessProfile;
        private String egressProfile;
        private double betaAccessTime = 1.0;
        private double betaEgressTime = 1.0;
        public String getProfile() { return profile; }
        public void setProfile(String profile) { this.profile = profile; }
        public String getLocale() { return locale; }
        public void setLocale(String locale) { this.locale = locale; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public boolean isInstructions() { return instructions; }
        public void setInstructions(boolean instructions) { this.instructions = instructions; }
        public boolean isGeometry() { return geometry; }
        public void setGeometry(boolean geometry) { this.geometry = geometry; }
        public boolean isElevation() { return elevation; }
        public void setElevation(boolean elevation) { this.elevation = elevation; }
        public boolean isPointsEncoded() { return pointsEncoded; }
        public void setPointsEncoded(boolean pointsEncoded) { this.pointsEncoded = pointsEncoded; }
        public String getDepartureTime() { return departureTime; }
        public void setDepartureTime(String departureTime) { this.departureTime = departureTime; }
        public boolean isArriveBy() { return arriveBy; }
        public void setArriveBy(boolean arriveBy) { this.arriveBy = arriveBy; }
        public Boolean getIgnoreTransfers() { return ignoreTransfers; }
        public void setIgnoreTransfers(Boolean ignoreTransfers) { this.ignoreTransfers = ignoreTransfers; }
        public Integer getLimitSolutions() { return limitSolutions; }
        public void setLimitSolutions(Integer limitSolutions) { this.limitSolutions = limitSolutions; }
        public String getLimitTripTime() { return limitTripTime; }
        public void setLimitTripTime(String limitTripTime) { this.limitTripTime = limitTripTime; }
        public String getLimitStreetTime() { return limitStreetTime; }
        public void setLimitStreetTime(String limitStreetTime) { this.limitStreetTime = limitStreetTime; }
        public String getAccessProfile() { return accessProfile; }
        public void setAccessProfile(String accessProfile) { this.accessProfile = accessProfile; }
        public String getEgressProfile() { return egressProfile; }
        public void setEgressProfile(String egressProfile) { this.egressProfile = egressProfile; }
        public double getBetaAccessTime() { return betaAccessTime; }
        public void setBetaAccessTime(double betaAccessTime) { this.betaAccessTime = betaAccessTime; }
        public double getBetaEgressTime() { return betaEgressTime; }
        public void setBetaEgressTime(double betaEgressTime) { this.betaEgressTime = betaEgressTime; }
    }
}
