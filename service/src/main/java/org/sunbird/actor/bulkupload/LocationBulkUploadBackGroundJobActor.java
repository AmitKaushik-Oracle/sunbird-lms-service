package org.sunbird.actor.bulkupload;

import akka.actor.ActorRef;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.exception.ProjectCommonException;
import org.sunbird.exception.ResponseCode;
import org.sunbird.keys.JsonKey;
import org.sunbird.model.bulkupload.BulkUploadProcess;
import org.sunbird.model.bulkupload.BulkUploadProcessTask;
import org.sunbird.model.location.Location;
import org.sunbird.operations.LocationActorOperation;
import org.sunbird.request.Request;
import org.sunbird.request.RequestContext;
import org.sunbird.response.Response;
import org.sunbird.service.location.LocationService;
import org.sunbird.service.location.LocationServiceImpl;
import org.sunbird.telemetry.dto.TelemetryEnvKey;
import org.sunbird.util.ProjectUtil;
import org.sunbird.util.Util;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

public class LocationBulkUploadBackGroundJobActor extends BaseBulkUploadBackgroundJobActor {

  private final LocationService locationService = new LocationServiceImpl();
  @Inject
  @Named("location_actor")
  private ActorRef locationActor;

  @Override
  public void onReceive(Request request) throws Throwable {

    String operation = request.getOperation();
    Util.initializeContext(request, TelemetryEnvKey.GEO_LOCATION);

    switch (operation) {
      case "locationBulkUploadBackground":
        handleBulkUploadBackground(
            request,
            (bulkUploadProcess) -> {
              processBulkUpload(
                  (BulkUploadProcess) bulkUploadProcess,
                  (tasks) -> {
                    processTasks((List<BulkUploadProcessTask>) tasks, request.getRequestContext());
                    return null;
                  },
                  request.getRequestContext());
              return null;
            });
        break;
      default:
        logger.info(operation + ": unsupported message");
    }
  }

  private void processLocation(BulkUploadProcessTask task, RequestContext context) {
    ObjectMapper mapper = new ObjectMapper();
    logger.info(context, "LocationBulkUploadBackGroundJobActor: processLocation called");
    String data = task.getData();
    try {

      Map<String, Object> row = mapper.readValue(data, Map.class);

      if (checkMandatoryFields(row, JsonKey.CODE)) {
        Location location = null;
        try {
          List<Location> locationList = locationService.locationSearch(JsonKey.CODE, row.get(JsonKey.CODE), context);
          if (CollectionUtils.isNotEmpty(locationList)) {
            location = locationList.get(0);
          }
        } catch (Exception ex) {
          setTaskStatus(task, ProjectUtil.BulkProcessStatus.FAILED, ex.getMessage(), row, null);
        }
        if (null == location) {
          callCreateLocation(row, task, context);
        } else {
          callUpdateLocation(row, mapper.convertValue(location, Map.class), task, context);
        }
      } else {
        setTaskStatus(
            task,
            ProjectUtil.BulkProcessStatus.FAILED,
            MessageFormat.format(
                ResponseCode.mandatoryParamsMissing.getErrorMessage(), JsonKey.CODE),
            row,
            null);
      }
    } catch (IOException e) {
      ProjectCommonException.throwClientErrorException(
          ResponseCode.SERVER_ERROR, ResponseCode.SERVER_ERROR.getErrorMessage());
    }
  }

  private boolean checkMandatoryFields(Map<String, Object> row, String... fields) {

    boolean flag = true;
    for (String field : fields) {
      if (!(row.containsKey(field))) {
        flag = false;
        break;
      }
    }
    return flag;
  }

  private void callUpdateLocation(
      Map<String, Object> row,
      Map<String, Object> response,
      BulkUploadProcessTask task,
      RequestContext context)
      throws JsonProcessingException {

    String id = (String) response.get(JsonKey.ID);
    row.put(JsonKey.ID, id);

    // since for update type is not allowed so remove from request body
    String locationType = (String) row.remove(JsonKey.LOCATION_TYPE);
    // check whether update for same type or different type.
    if (!(areLocationTypesEqual(locationType, (String) response.get(JsonKey.LOCATION_TYPE)))) {
      row.put(JsonKey.LOCATION_TYPE, response.get(JsonKey.LOCATION_TYPE));
      setTaskStatus(
          task,
          ProjectUtil.BulkProcessStatus.FAILED,
          MessageFormat.format(
              ResponseCode.unupdatableField.getErrorMessage(), JsonKey.LOCATION_TYPE),
          row,
          JsonKey.UPDATE);
      return;
    }
    ObjectMapper mapper = new ObjectMapper();
    try {
      Request request = new Request();
      request.getRequest().putAll(row);
      request.setOperation(LocationActorOperation.UPDATE_LOCATION.getValue());
      logger.info(context, "callUpdateLocation ");
      locationActor.tell(request, self());

    } catch (Exception ex) {
      logger.error(
          context,
          "LocationBulkUploadBackGroundJobActor : callUpdateLocation - got exception "
              + ex.getMessage(),
          ex);
      row.put(JsonKey.ERROR_MSG, ex.getMessage());
      setTaskStatus(
          task, ProjectUtil.BulkProcessStatus.FAILED, ex.getMessage(), row, JsonKey.UPDATE);
    }

    row.put(JsonKey.LOCATION_TYPE, locationType);
    task.setData(mapper.writeValueAsString(row));
    setSuccessTaskStatus(task, ProjectUtil.BulkProcessStatus.COMPLETED, row, JsonKey.UPDATE);
  }

  private Boolean areLocationTypesEqual(String locationType, String responseType) {
    return (locationType.equalsIgnoreCase(responseType));
  }

  private void callCreateLocation(
      Map<String, Object> row, BulkUploadProcessTask task, RequestContext context)
      throws JsonProcessingException {

    Request request = new Request();
    request.getRequest().putAll(row);
    request.setRequestContext(context);
    String locationId = "";
    try {
      request.setOperation(LocationActorOperation.CREATE_LOCATION.getValue());
      logger.info(context, "callCreateLocation ");
      Object obj = actorCall(locationActor, request, context);
      if (obj instanceof Response) {
        Response response = (Response) obj;
        locationId = (String) response.get(JsonKey.ID);
      }
    } catch (Exception ex) {
      logger.error(
          context,
          "LocationBulkUploadBackGroundJobActor : callCreateLocation - got exception "
              + ex.getMessage(),
          ex);
      setTaskStatus(
          task, ProjectUtil.BulkProcessStatus.FAILED, ex.getMessage(), row, JsonKey.CREATE);
      return;
    }

    if (StringUtils.isEmpty(locationId)) {
      logger.info(
          context,
          "LocationBulkUploadBackGroundJobActor : Null receive from interservice communication");
      setTaskStatus(
          task,
          ProjectUtil.BulkProcessStatus.FAILED,
          ResponseCode.internalError.getErrorMessage(),
          row,
          JsonKey.CREATE);
    } else {
      row.put(JsonKey.ID, locationId);
      setSuccessTaskStatus(task, ProjectUtil.BulkProcessStatus.COMPLETED, row, JsonKey.CREATE);
    }
  }

  private void processTasks(List<BulkUploadProcessTask> tasks, RequestContext context) {
    for (BulkUploadProcessTask task : tasks) {
      if (task.getStatus() != null
          && task.getStatus() != ProjectUtil.BulkProcessStatus.COMPLETED.getValue()) {
        processLocation(task, context);
        task.setLastUpdatedOn(new Timestamp(System.currentTimeMillis()));
        task.setIterationId(task.getIterationId() + 1);
      }
    }
  }

  private Object actorCall(ActorRef actorRef, Request request, RequestContext context) {
    Object obj = null;
    try {
      Timeout t = new Timeout(Duration.create(10, TimeUnit.SECONDS));
      Future<Object> future = Patterns.ask(actorRef, request, t);
      obj = Await.result(future, t.duration());
    } catch (ProjectCommonException pce) {
      throw pce;
    } catch (Exception e) {
      logger.error(
              context,
              "Unable to communicate with actor: Exception occurred with error message = "
                      + e.getMessage(),
              e);
      ProjectCommonException.throwServerErrorException(
              ResponseCode.unableToCommunicateWithActor,
              ResponseCode.unableToCommunicateWithActor.getErrorMessage());
    }
    checkLocationResponseForException(obj);
    return obj;
  }

  private void checkLocationResponseForException(Object obj) {
    if (obj instanceof ProjectCommonException) {
      throw (ProjectCommonException) obj;
    } else if (obj instanceof Exception) {
      throw new ProjectCommonException(
              ResponseCode.SERVER_ERROR.getErrorCode(),
              ResponseCode.SERVER_ERROR.getErrorMessage(),
              ResponseCode.SERVER_ERROR.getResponseCode());
    }
  }

  @Override
  public void preProcessResult(Map<String, Object> result) {
    // Do nothing
  }
}
