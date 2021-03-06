package dcos.metronome
package jobrun.impl

import java.time.{ Clock, LocalDateTime, ZoneOffset }
import java.util.concurrent.LinkedBlockingDeque

import akka.actor.{ ActorSystem, Props }
import akka.testkit._
import dcos.metronome.jobrun.StartedJobRun
import dcos.metronome.jobrun.impl.JobRunExecutorActor.{ Aborted, Finished }
import dcos.metronome.jobrun.impl.JobRunServiceActor._
import dcos.metronome.model._
import dcos.metronome.repository.impl.InMemoryRepository
import dcos.metronome.utils.test.Mockito
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.dummy.DummyMetrics
import org.scalatest._
import org.scalatest.concurrent.{ Eventually, ScalaFutures }

import scala.concurrent.{ Future, Promise }

class JobRunServiceActorTest extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll
    with GivenWhenThen with ScalaFutures with Matchers with Eventually with ImplicitSender with Mockito {

  test("List runs will list all running services") {
    Given("A service with 2 jobRuns")
    val f = new Fixture
    val actor = f.serviceActor
    actor.underlyingActor.allJobRuns += f.run1.jobRun.id -> f.run1
    actor.underlyingActor.allJobRuns += f.run2.jobRun.id -> f.run2

    When("The list of started job runs is queried")
    actor ! ListRuns(_ => true)

    Then("The list of started job runs is returned")
    val result = expectMsgClass(classOf[Iterable[StartedJobRun]])
    result should have size 2
    result should contain(f.run1)
    result should contain(f.run2)
    system.stop(actor)
  }

  test("Get a specific job") {
    Given("A service with 2 jobRuns")
    val f = new Fixture
    val actor = f.serviceActor
    actor.underlyingActor.allJobRuns += f.run1.jobRun.id -> f.run1
    actor.underlyingActor.allJobRuns += f.run2.jobRun.id -> f.run2

    When("An existing jobRun is queried")
    actor ! GetJobRun(f.run1.jobRun.id)

    Then("The job run is returned")
    expectMsg(Some(f.run1))

    When("A non existing jobRun is queried")
    actor ! GetJobRun(JobRunId(f.jobSpec))

    Then("None is returned")
    expectMsg(None)
    system.stop(actor)
  }

  test("Get all jobRuns for one spec") {
    Given("A service with 2 jobRuns")
    val f = new Fixture
    val actor = f.serviceActor
    actor.underlyingActor.allJobRuns += f.run1.jobRun.id -> f.run1
    actor.underlyingActor.allJobRuns += f.run2.jobRun.id -> f.run2

    When("An existing jobRun is queried")
    actor ! GetActiveJobRuns(f.jobSpec.id)

    Then("The list of started job runs is returned")
    val result = expectMsgClass(classOf[Iterable[StartedJobRun]])
    result should have size 2

    When("A non existing jobRun is queried")
    actor ! GetActiveJobRuns(JobId("n/a"))

    Then("An empty list is returned")
    expectMsg(Iterable.empty)
    system.stop(actor)
  }

  test("Triggering a jobRun works") {
    Given("An empty service")
    val f = new Fixture
    val actor = f.serviceActor

    When("An existing jobRun is queried")
    actor ! TriggerJobRun(f.jobSpec, None)

    Then("The list of started job runs is returned")
    val started = expectMsgClass(classOf[StartedJobRun])
    started.jobRun.jobSpec should be(f.jobSpec)
    actor.underlyingActor.allJobRuns should have size 1
    system.stop(actor)
  }

  test("Triggering a jobRun with forbid policy and no job run works") {
    Given("An empty service")
    val f = new Fixture
    val actor = f.serviceActor

    When("An existing jobRun is queried")
    actor ! TriggerJobRun(f.jobSpec, Some(f.forbidSchedule))

    Then("The list of started job runs is returned")
    val started = expectMsgClass(classOf[StartedJobRun])
    started.jobRun.jobSpec should be(f.jobSpec)
    actor.underlyingActor.allJobRuns should have size 1
    system.stop(actor)
  }

  test("Triggering a jobRun with forbid policy and a current job run does NOT start another job run") {
    Given("A service with 1 jobRuns")
    val f = new Fixture
    val actor = f.serviceActor
    // triggered job
    actor ! TriggerJobRun(f.jobSpec, None)
    val startedRun = expectMsgClass(classOf[StartedJobRun])

    When("An existing jobRun is queried")
    actor ! TriggerJobRun(f.jobSpec, Some(f.forbidSchedule))

    Then("No Job Started and No Returned StartedJobRun")
    expectNoMsg()
    actor.underlyingActor.allJobRuns should have size 1
  }

  test("A finished job run will be removed from the registry") {
    Given("An empty service")
    val f = new Fixture
    val actor = f.serviceActor
    actor ! TriggerJobRun(f.jobSpec, None)
    val startedRun = expectMsgClass(classOf[StartedJobRun])

    When("The job finished")
    val result = JobResult(startedRun.jobRun)
    actor ! Finished(result)

    Then("The job run is removed from the registry")
    eventually(actor.underlyingActor.allJobRuns should have size 0)
    eventually(actor.underlyingActor.allRunExecutors should have size 0)
  }

  test("An aborted job run will be removed from the registry") {
    Given("An empty service")
    val f = new Fixture
    val actor = f.serviceActor
    actor ! TriggerJobRun(f.jobSpec, None)
    val startedRun = expectMsgClass(classOf[StartedJobRun])

    When("The job aborted")
    val result = JobResult(startedRun.jobRun)
    actor ! Aborted(result)

    Then("The job run is removed from the registry")
    eventually(actor.underlyingActor.allJobRuns should have size 0)
    eventually(actor.underlyingActor.allRunExecutors should have size 0)
  }

  test("A failed job run will be removed from the registry") {
    Given("An empty service")
    val f = new Fixture
    val actor = f.serviceActor
    actor ! TriggerJobRun(f.jobSpec, None)
    val startedRun = expectMsgClass(classOf[StartedJobRun])

    When("The job finished")
    val result = JobResult(startedRun.jobRun)
    actor ! JobRunExecutorActor.Failed(result)

    Then("The job run is removed from the registry")
    eventually(actor.underlyingActor.allJobRuns should have size 0)
    eventually(actor.underlyingActor.allRunExecutors should have size 0)
  }

  test("Kill Job Run works") {
    Given("A service with 2 jobRuns")
    val f = new Fixture
    val actor = f.serviceActor
    actor ! TriggerJobRun(f.jobSpec, None)
    val startedRun = expectMsgClass(classOf[StartedJobRun])

    When("An existing jobRun is queried")
    actor ! KillJobRun(startedRun.jobRun.id)
    //signal the run is aborted
    actor ! Aborted(JobResult(startedRun.jobRun))

    Then("The list of started job runs is returned")
    expectMsg(startedRun)

    And("The executor sends a task aborted")
    val result = JobResult(startedRun.jobRun)
    actor ! Aborted(result)

    Then("The list of started job runs is returned")
    eventually(actor.underlyingActor.allJobRuns should have size 0)
    eventually(actor.underlyingActor.allRunExecutors should have size 0)
  }

  override protected def afterAll(): Unit = {
    shutdown()
  }

  class Fixture {
    val id = JobId("test")
    val jobSpec = JobSpec(id)
    val forbidSchedule = ScheduleSpec("schedule", CronSpec("* * * * *"), ScheduleSpec.DefaultTimeZone, ScheduleSpec.DefaultStartingDeadline, ConcurrencyPolicy.Forbid)

    val clock = new SettableClock(Clock.fixed(LocalDateTime.parse("2016-06-01T08:50:12.000").toInstant(ZoneOffset.UTC), ZoneOffset.UTC))

    def run() = {
      val jobRun = JobRun(JobRunId(jobSpec), jobSpec, JobRunStatus.Active, clock.instant(), None, None, Map.empty[Task.Id, JobRunTask])
      StartedJobRun(jobRun, Future.successful(JobResult(jobRun)))
    }
    val run1 = run()
    val run2 = run()

    val dummyQueue = new LinkedBlockingDeque[TestActor.Message]()
    val dummyProp = Props(new TestActor(dummyQueue))
    val repo = new InMemoryRepository[JobRunId, JobRun]
    val metrics = DummyMetrics

    val createExecutor: (JobRun, Promise[JobResult]) => Props = (_, _) => dummyProp
    def serviceActor = TestActorRef[JobRunServiceActor](JobRunServiceActor.props(clock, createExecutor, repo, metrics))
  }
}
