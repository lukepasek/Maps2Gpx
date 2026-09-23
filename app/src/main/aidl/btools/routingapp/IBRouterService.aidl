package btools.routingapp;

/**
 * Copy of BRouter's own service interface, so we can bind to the installed BRouter app.
 *
 * The package and file path must match BRouter's exactly - AIDL identity is by fully
 * qualified name, not by file location in our project.
 *
 * Source: https://github.com/abrensch/brouter
 *         brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl
 */
interface IBRouterService {
    String getTrackFromParams(in Bundle params);
}
