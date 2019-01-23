package org.camunda.bpm.spring.boot.starter;

import org.assertj.core.util.DateUtil;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.impl.history.event.HistoricIdentityLinkLogEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoryEvent;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.spring.boot.starter.test.nonpa.TestApplication;
import org.camunda.bpm.spring.boot.starter.test.nonpa.TestEventCaptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.junit4.SpringRunner;

import javax.transaction.Transactional;
import java.util.Date;

import static junit.framework.TestCase.fail;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
@SpringBootTest(
  classes = {TestApplication.class},
  webEnvironment = WebEnvironment.NONE,
  properties = {"camunda.bpm.history-level=full"}
)
@Transactional
public class CamundaEventingIT extends AbstractCamundaAutoConfigurationIT {

  @Autowired
  private RuntimeService runtime;

  @Autowired
  private TaskService taskService;

  @Autowired
  private TestEventCaptor eventCaptor;

  private ProcessInstance instance;

  @Before
  public void init() {
    ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
      .processDefinitionKey("eventing")
      .singleResult();
    assertThat(processDefinition).isNotNull();

    eventCaptor.historyEvents.clear();
    instance = runtime.startProcessInstanceByKey("eventing");
  }

  @After
  public void stop() {
    if (instance != null) {
      // update stale instance
      instance = runtime.createProcessInstanceQuery().processInstanceId(instance.getProcessInstanceId()).active().singleResult();
      if (instance != null) {
        runtime.deleteProcessInstance(instance.getProcessInstanceId(), "eventing shutdown");
      }
    }
  }

  @Test
  public final void should_event_task_creation() {
    assertThat(eventCaptor.taskEvents).isNotEmpty();

    Task task = taskService.createTaskQuery().active().singleResult();
    TestEventCaptor.TaskEvent taskEvent = eventCaptor.taskEvents.pop();

    assertThat(taskEvent.eventName).isEqualTo("create");
    assertThat(taskEvent.id).isEqualTo(task.getId());
    assertThat(taskEvent.processInstanceId).isEqualTo(task.getProcessInstanceId());
  }

  @Test
  public final void should_event_task_assignment() {

    // given
    assertThat(eventCaptor.taskEvents).isNotEmpty();
    eventCaptor.taskEvents.clear();
    Task task = taskService.createTaskQuery().active().singleResult();

    // when
    taskService.setAssignee(task.getId(), "kermit");

    // then
    TestEventCaptor.TaskEvent taskEvent = eventCaptor.taskEvents.pop();
    assertThat(taskEvent.eventName).isEqualTo("assignment");
    assertThat(taskEvent.id).isEqualTo(task.getId());
    assertThat(taskEvent.processInstanceId).isEqualTo(task.getProcessInstanceId());
  }


  @Test
  public final void should_event_task_complete() {

    // given
    assertThat(eventCaptor.taskEvents).isNotEmpty();
    eventCaptor.taskEvents.clear();
    Task task = taskService.createTaskQuery().active().singleResult();

    // when
    taskService.complete(task.getId());

    // then
    TestEventCaptor.TaskEvent taskEvent = eventCaptor.taskEvents.pop();
    assertThat(taskEvent.eventName).isEqualTo("complete");
    assertThat(taskEvent.id).isEqualTo(task.getId());
    assertThat(taskEvent.processInstanceId).isEqualTo(task.getProcessInstanceId());
  }

  @Test
  public final void should_event_task_delete() {

    // given
    assertThat(eventCaptor.taskEvents).isNotEmpty();
    eventCaptor.taskEvents.clear();
    Task task = taskService.createTaskQuery().active().singleResult();

    // when
    runtimeService.deleteProcessInstance(instance.getProcessInstanceId(), "no need");

    // then
    TestEventCaptor.TaskEvent taskEvent = eventCaptor.taskEvents.pop();
    assertThat(taskEvent.eventName).isEqualTo("delete");
    assertThat(taskEvent.id).isEqualTo(task.getId());
    assertThat(taskEvent.processInstanceId).isEqualTo(task.getProcessInstanceId());
  }

  @Test
  public final void should_event_execution() {

    // given
    assertThat(eventCaptor.executionEvents).isNotEmpty();
    eventCaptor.executionEvents.clear();
    Task task = taskService.createTaskQuery().active().singleResult();

    // when
    taskService.complete(task.getId());

    // then 7
    // 2 for user task (start, end)
    // 3 for service task (start, take, end)
    // 2 for end event (start, end)
    assertThat(eventCaptor.executionEvents.size()).isEqualTo(2 + 3 + 2);
  }

  @Test
  public final void should_event_history_task_assignment_changes() {
    // given
    assertThat(eventCaptor.historyEvents).isNotEmpty();
    eventCaptor.historyEvents.clear();
    assertThat(eventCaptor.historyEvents).isEmpty();

    Task task = taskService.createTaskQuery().active().singleResult();

    // when
    taskService.addCandidateUser(task.getId(), "userId");
    taskService.addCandidateGroup(task.getId(), "groupId");
    taskService.deleteCandidateUser(task.getId(), "userId");
    taskService.deleteCandidateGroup(task.getId(), "groupId");

    // then in reverse order

    // Remove candidate group
    HistoryEvent candidateGroupEvent = eventCaptor.historyEvents.pop();
    assertThat(candidateGroupEvent.getEventType()).isEqualTo("delete-identity-link");
    if (candidateGroupEvent instanceof HistoricIdentityLinkLogEventEntity) {
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateGroupEvent).getType()).isEqualTo("candidate");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateGroupEvent).getOperationType()).isEqualTo("delete");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateGroupEvent).getGroupId()).isEqualTo("groupId");
    } else {
      fail("Expected identity link log event");
    }


    // Remove candidate user
    HistoryEvent candidateUserEvent = eventCaptor.historyEvents.pop();
    assertThat(candidateUserEvent.getEventType()).isEqualTo("delete-identity-link");
    if (candidateUserEvent instanceof HistoricIdentityLinkLogEventEntity) {
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getType()).isEqualTo("candidate");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getOperationType()).isEqualTo("delete");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getUserId()).isEqualTo("userId");
    } else {
      fail("Expected identity link log event");
    }

    // Add candidate group
    candidateGroupEvent = eventCaptor.historyEvents.pop();
    assertThat(candidateGroupEvent.getEventType()).isEqualTo("add-identity-link");
    if (candidateGroupEvent instanceof HistoricIdentityLinkLogEventEntity) {
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateGroupEvent).getType()).isEqualTo("candidate");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateGroupEvent).getOperationType()).isEqualTo("add");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateGroupEvent).getGroupId()).isEqualTo("groupId");
    } else {
      fail("Expected identity link log event");
    }

    // Add candidate user
    candidateUserEvent = eventCaptor.historyEvents.pop();
    assertThat(candidateUserEvent.getEventType()).isEqualTo("add-identity-link");
    if (candidateUserEvent instanceof HistoricIdentityLinkLogEventEntity) {
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getType()).isEqualTo("candidate");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getOperationType()).isEqualTo("add");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getUserId()).isEqualTo("userId");
    } else {
      fail("Expected identity link log event");
    }

    assertThat(eventCaptor.historyEvents).isEmpty();
  }


  @Test
  public void should_event_history_task_attribute_changes() {
    assertThat(eventCaptor.historyEvents).isNotEmpty();
    eventCaptor.historyEvents.clear();

    Task task = taskService.createTaskQuery().active().singleResult();

    task.setName("new Name");
    taskService.saveTask(task);


    HistoryEvent taskChangeEvent = eventCaptor.historyEvents.pop();
    assertThat(taskChangeEvent.getEventType()).isEqualTo("update");
    if (taskChangeEvent instanceof HistoricTaskInstanceEventEntity) {
      assertThat(((HistoricTaskInstanceEventEntity) taskChangeEvent).getName()).isEqualTo("new Name");
    } else {
      fail("Expected task instance change event");
    }

  }

  @Test
  public void should_event_history_task_multiple_assignment_changes() {

    // given
    assertThat(eventCaptor.historyEvents).isNotEmpty();
    eventCaptor.historyEvents.clear();
    assertThat(eventCaptor.historyEvents).isEmpty();

    Task task = taskService.createTaskQuery().active().singleResult();

    // when
    taskService.addCandidateUser(task.getId(), "user1");
    taskService.addCandidateUser(task.getId(), "user2");

    // then in reverse order

    // Add candidate user
    HistoryEvent candidateUserEvent = eventCaptor.historyEvents.pop();
    assertThat(candidateUserEvent.getEventType()).isEqualTo("add-identity-link");
    if (candidateUserEvent instanceof HistoricIdentityLinkLogEventEntity) {
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getType()).isEqualTo("candidate");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getOperationType()).isEqualTo("add");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getUserId()).isEqualTo("user2");
    } else {
      fail("Expected identity link log event");
    }

    // Add candidate user
    candidateUserEvent = eventCaptor.historyEvents.pop();
    assertThat(candidateUserEvent.getEventType()).isEqualTo("add-identity-link");
    if (candidateUserEvent instanceof HistoricIdentityLinkLogEventEntity) {
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getType()).isEqualTo("candidate");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getOperationType()).isEqualTo("add");
      assertThat(((HistoricIdentityLinkLogEventEntity) candidateUserEvent).getUserId()).isEqualTo("user1");
    } else {
      fail("Expected identity link log event");
    }

    assertThat(eventCaptor.historyEvents).isEmpty();
  }

  @Test
  public void should_event_history_task_follow_up_date_changes() {
    assertThat(eventCaptor.historyEvents).isNotEmpty();
    eventCaptor.historyEvents.clear();

    Task task = taskService.createTaskQuery().active().singleResult();

    Date now = DateUtil.now();

    task.setFollowUpDate(now);
    taskService.saveTask(task);

    HistoryEvent taskChangeEvent = eventCaptor.historyEvents.pop();
    assertThat(taskChangeEvent.getEventType()).isEqualTo("update");
    if (taskChangeEvent instanceof HistoricTaskInstanceEventEntity) {
      assertThat(((HistoricTaskInstanceEventEntity) taskChangeEvent).getFollowUpDate()).isEqualTo(now);
    } else {
      fail("Expected task instance change event");
    }
  }
}
