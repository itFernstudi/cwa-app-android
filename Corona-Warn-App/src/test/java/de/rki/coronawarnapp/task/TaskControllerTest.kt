package de.rki.coronawarnapp.task

import de.rki.coronawarnapp.bugreporting.reportProblem
import de.rki.coronawarnapp.exception.reporting.report
import de.rki.coronawarnapp.task.common.DefaultTaskRequest
import de.rki.coronawarnapp.task.common.Finished
import de.rki.coronawarnapp.task.testtasks.SkippingTask
import de.rki.coronawarnapp.task.testtasks.alerterror.AlertErrorTask
import de.rki.coronawarnapp.task.testtasks.precondition.PreconditionTask
import de.rki.coronawarnapp.task.testtasks.queue.QueueingTask
import de.rki.coronawarnapp.task.testtasks.silenterror.SilentErrorTask
import de.rki.coronawarnapp.task.testtasks.timeout.TimeoutTask
import de.rki.coronawarnapp.task.testtasks.timeout.TimeoutTask2
import de.rki.coronawarnapp.task.testtasks.timeout.TimeoutTaskArguments
import de.rki.coronawarnapp.util.TimeStamper
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.instanceOf
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.joda.time.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseIOTest
import testhelpers.coroutines.runTest2
import testhelpers.coroutines.runWithoutChildExceptionCancellation
import testhelpers.coroutines.test
import testhelpers.extensions.isAfterOrEqual
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

class TaskControllerTest : BaseIOTest() {

    private val taskFactoryMap: MutableMap<
        Class<out Task<Task.Progress, Task.Result>>,
        TaskFactory<out Task.Progress, out Task.Result>> = mutableMapOf()
    @MockK lateinit var timeStamper: TimeStamper

    private val testDir = File(IO_TEST_BASEDIR, this::class.java.simpleName)

    private val timeoutFactory = spyk(TimeoutTask.Factory { TimeoutTask() })
    private val timeoutFactory2 = spyk(TimeoutTask2.Factory { TimeoutTask2() })
    private val queueingFactory = spyk(QueueingTask.Factory { QueueingTask() })
    private val skippingFactory = spyk(SkippingTask.Factory { SkippingTask() })
    private val preconditionFactory = spyk(PreconditionTask.Factory { PreconditionTask() })
    private val silentErrorFactory = spyk(SilentErrorTask.Factory { SilentErrorTask() })
    private val alertErrorFactory = spyk(AlertErrorTask.Factory { AlertErrorTask() })

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)

        taskFactoryMap[QueueingTask::class.java] = queueingFactory
        taskFactoryMap[SkippingTask::class.java] = skippingFactory
        taskFactoryMap[TimeoutTask::class.java] = timeoutFactory
        taskFactoryMap[TimeoutTask2::class.java] = timeoutFactory2
        taskFactoryMap[PreconditionTask::class.java] = preconditionFactory
        taskFactoryMap[SilentErrorTask::class.java] = silentErrorFactory
        taskFactoryMap[AlertErrorTask::class.java] = alertErrorFactory

        every { timeStamper.nowUTC } answers {
            Instant.now()
        }
    }

    @AfterEach
    fun teardown() {
        taskFactoryMap.clear()
        testDir.deleteRecursively()
    }

    private fun createInstance(scope: CoroutineScope) = TaskController(
        taskFactories = taskFactoryMap,
        taskScope = scope,
        timeStamper = timeStamper
    )

    @Test
    fun `side effect free init`() = runTest {
        shouldNotThrowAny {
            val instance = createInstance(scope = this)
            instance.close()
        }
    }

    @Test
    fun `missing task factory throw exception`() = runTest {
        val instance = createInstance(scope = this)

        val unknownTask = DefaultTaskRequest(
            type = Task::class,
            arguments = mockk()
        )

        shouldThrow<MissingTaskFactoryException> {
            instance.submit(unknownTask)
        }

        instance.close()
    }

    @Test
    fun `task map is empty by default`() = runTest {
        val instance = createInstance(scope = this)

        val map = instance.tasks.take(1).toList().single()
        map shouldBe emptyList()

        instance.close()
    }

    @Test
    fun `default task execution`() = runTest2 {
        val instance = createInstance(scope = this)

        val arguments = QueueingTask.Arguments(
            path = File(testDir, UUID.randomUUID().toString())
        )
        val request = DefaultTaskRequest(
            type = QueueingTask::class,
            arguments = arguments
        )

        arguments.path.exists() shouldBe false

        instance.submit(request)

        val infoRunning = instance.tasks.first().single()
        infoRunning.apply {
            taskState.executionState shouldBe TaskState.ExecutionState.RUNNING
            taskState.startedAt!!.isAfterOrEqual(taskState.createdAt) shouldBe true

            taskState.isActive shouldBe true

            shouldThrowAny {
                taskState.resultOrThrow shouldBe null
            }
        }
        val progressCollector = infoRunning.progress.test(startOnScope = this)

        this.advanceUntilIdle()

        val infoFinished = instance.tasks
            .first { it.single().taskState.executionState == TaskState.ExecutionState.FINISHED }
            .single()

        arguments.path.exists() shouldBe true

        progressCollector.latestValue shouldBe Finished

        infoFinished.apply {

            taskState.isSuccessful shouldBe true
            taskState.resultOrThrow shouldNotBe null

            taskState.startedAt!!.isAfterOrEqual(taskState.createdAt) shouldBe true
            taskState.finishedAt!!.isAfterOrEqual(taskState.startedAt!!) shouldBe true

            taskState.error shouldBe null

            (taskState.result as QueueingTask.Result).apply {
                writtenBytes shouldBe arguments.path.length()
            }
        }

        coVerifySequence {
            queueingFactory.createConfig()
            queueingFactory.taskProvider
        }

        instance.close()
    }

    @Test
    fun `failed task yields exception`() = runTest {
        runWithoutChildExceptionCancellation {
            val instance = createInstance(scope = this)

            val arguments = QueueingTask.Arguments(
                path = File(testDir, UUID.randomUUID().toString())
            )
            val request = DefaultTaskRequest(
                type = QueueingTask::class,
                arguments = arguments
            )

            arguments.path.exists() shouldBe false

            // The target path is now a directory, this will fail the task
            arguments.path.mkdirs()

            instance.submit(request)

            advanceUntilIdle()

            val infoFinished = instance.tasks
                .first { it.single().taskState.executionState == TaskState.ExecutionState.FINISHED }
                .single()

            infoFinished.apply {
                taskState.startedAt!!.isAfterOrEqual(taskState.createdAt) shouldBe true
                taskState.finishedAt!!.isAfterOrEqual(taskState.startedAt!!) shouldBe true

                taskState.isSuccessful shouldBe false
                taskState.isFailed shouldBe true

                taskState.result shouldBe null
                taskState.error should instanceOf(FileNotFoundException::class)
            }

            instance.close()
        }
    }

    @Test
    fun `canceled task yields exception`() = runTest2 {
        val instance = createInstance(scope = this)

        val arguments = QueueingTask.Arguments(
            path = File(testDir, UUID.randomUUID().toString())
        )
        val request = DefaultTaskRequest(
            type = QueueingTask::class,
            arguments = arguments
        )
        instance.submit(request)
        delay(1000)
        instance.cancel(request.id)

        val infoFinished = instance.tasks
            .first { it.single().taskState.executionState == TaskState.ExecutionState.FINISHED }
            .single()

        infoFinished.taskState.error shouldBe instanceOf(TaskCancellationException::class)

        instance.close()
    }

    @Test
    fun `queued task execution`() = runTest2 {
        val instance = createInstance(scope = this)

        val arguments = QueueingTask.Arguments(
            path = File(testDir, UUID.randomUUID().toString())
        )
        arguments.path.exists() shouldBe false

        val request1 = DefaultTaskRequest(
            type = QueueingTask::class,
            arguments = arguments
        )
        instance.submit(request1)

        val request2 = request1.toNewTask()
        instance.submit(request2)

        val infoPending = instance.tasks.first { emission ->
            emission.any { it.taskState.executionState == TaskState.ExecutionState.PENDING }
        }
        infoPending.size shouldBe 2
        infoPending.single { it.taskState.request == request1 }.apply {
            taskState.executionState shouldBe TaskState.ExecutionState.RUNNING
        }
        infoPending.single { it.taskState.request == request2 }.apply {
            taskState.executionState shouldBe TaskState.ExecutionState.PENDING
        }

        this.advanceUntilIdle()

        val infoFinished = instance.tasks.first { emission ->
            emission.any { it.taskState.executionState == TaskState.ExecutionState.FINISHED }
        }
        infoFinished.size shouldBe 2

        // Let's make sure both tasks actually ran
        infoFinished.single { it.taskState.request == request2 }.apply {
            val result = taskState.resultOrThrow as QueueingTask.Result
            arguments.path.length() shouldBe result.writtenBytes
        }
        infoFinished.single { it.taskState.request == request1 }.apply {
            val result = taskState.resultOrThrow as QueueingTask.Result
            arguments.path.length() shouldNotBe result.writtenBytes
        }

        arguments.path.length() shouldBe 720L

        instance.close()
    }

    @Test
    fun `skippable tasks are skipped`() = runTest2 {
        val instance = createInstance(scope = this)

        val arguments = QueueingTask.Arguments(
            path = File(testDir, UUID.randomUUID().toString())
        )
        arguments.path.exists() shouldBe false

        val request1 = DefaultTaskRequest(
            type = SkippingTask::class,
            arguments = arguments
        )
        instance.submit(request1)

        val request2 = DefaultTaskRequest(
            type = SkippingTask::class,
            arguments = arguments
        )
        instance.submit(request2)

        this.advanceUntilIdle()

        val infoFinished = instance.tasks.first { emission ->
            emission.any { it.taskState.executionState == TaskState.ExecutionState.FINISHED }
        }
        infoFinished.size shouldBe 2

        infoFinished.single { it.taskState.request == request1 }.apply {
            taskState.type shouldBe SkippingTask::class
            taskState.isSkipped shouldBe false
            taskState.resultOrThrow shouldNotBe null
        }
        infoFinished.single { it.taskState.request == request2 }.apply {
            taskState.type shouldBe SkippingTask::class
            taskState.isSkipped shouldBe true
            taskState.result shouldBe null
            taskState.error shouldBe null
        }

        arguments.path.length() shouldBe 360L

        instance.close()
    }

    @Test
    fun `tasks with preconditions that are not met are skipped`() = runTest2 {
        val instance = createInstance(scope = this)

        val request = DefaultTaskRequest(type = PreconditionTask::class)
        preconditionFactory.arePreconditionsMet = false
        instance.submit(request)

        advanceUntilIdle()

        val request2 = DefaultTaskRequest(type = PreconditionTask::class)
        preconditionFactory.arePreconditionsMet = true
        instance.submit(request2)

        this.advanceUntilIdle()

        val infoFinished = instance.tasks.first { emission ->
            emission.any { it.taskState.executionState == TaskState.ExecutionState.FINISHED }
        }
        infoFinished.size shouldBe 2

        infoFinished.single { it.taskState.request == request }.apply {
            taskState.type shouldBe PreconditionTask::class
            taskState.isSkipped shouldBe true
            taskState.result shouldBe null
            taskState.error shouldBe null
        }
        infoFinished.single { it.taskState.request == request2 }.apply {
            taskState.type shouldBe PreconditionTask::class
            taskState.isSkipped shouldBe false
            taskState.result shouldNotBe null
            taskState.error shouldBe null
        }

        instance.close()
    }

    @Test
    fun `collision behavior only affects task of same type`() = runTest2 {
        val arguments = QueueingTask.Arguments(path = File(testDir, UUID.randomUUID().toString()))
        arguments.path.exists() shouldBe false

        val request1 = DefaultTaskRequest(
            type = QueueingTask::class,
            arguments = arguments
        )

        // Class needs to be different, typing is based on that.
        val request2 = DefaultTaskRequest(
            type = SkippingTask::class,
            arguments = arguments
        )

        val instance = createInstance(scope = this)

        instance.submit(request1)
        instance.submit(request2)

        this.advanceUntilIdle()

        val infoFinished = instance.tasks.first { emission ->
            emission.any { it.taskState.executionState == TaskState.ExecutionState.FINISHED }
        }
        infoFinished.size shouldBe 2

        infoFinished.single { it.taskState.request == request1 }.apply {
            taskState.isSkipped shouldBe false
            taskState.resultOrThrow shouldNotBe null
        }
        infoFinished.single { it.taskState.request == request2 }.apply {
            taskState.isSkipped shouldBe false
            taskState.resultOrThrow shouldNotBe null
        }

        arguments.path.length() shouldBe 720L

        coVerifySequence {
            queueingFactory.createConfig()
            queueingFactory.taskProvider
            skippingFactory.createConfig()
            skippingFactory.taskProvider
        }

        instance.close()
    }

    @Test
    fun `resubmitting a request has no effect`() = runTest2 {
        val instance = createInstance(scope = this)

        val arguments = QueueingTask.Arguments(
            path = File(testDir, UUID.randomUUID().toString())
        )
        val request = DefaultTaskRequest(
            type = QueueingTask::class,
            arguments = arguments
        )

        arguments.path.exists() shouldBe false

        instance.submit(request)
        instance.submit(request)

        val infoFinished = instance.tasks
            .first { it.single().taskState.executionState == TaskState.ExecutionState.FINISHED }
            .single()

        infoFinished.apply {
            (taskState.resultOrThrow as QueueingTask.Result).apply {
                writtenBytes shouldBe arguments.path.length()
            }
        }

        instance.tasks.first().size shouldBe 1

        instance.close()
    }

    @Test
    fun `tasks are timed out according to their config`() = runTest2 {
        val instance = createInstance(scope = this)

        val request = DefaultTaskRequest(
            type = TimeoutTask::class,
            arguments = TimeoutTaskArguments()
        )

        instance.submit(request)

        val infoFinished = instance.tasks
            .first { it.single().taskState.executionState == TaskState.ExecutionState.FINISHED }
            .single()

        infoFinished.apply {
            taskState.isFailed shouldBe true
            taskState.error shouldBe instanceOf(TimeoutCancellationException::class)
        }

        instance.tasks.first().size shouldBe 1

        instance.close()
    }

    @Test
    fun `timeout starts on execution, not while pending`() = runTest2 {
        val instance = createInstance(scope = this)

        val taskWithTimeout = DefaultTaskRequest(
            type = TimeoutTask::class,
            arguments = TimeoutTaskArguments()
        )
        val taskWithoutTimeout = DefaultTaskRequest(
            type = TimeoutTask::class,
            arguments = TimeoutTaskArguments(delay = 5000)
        )
        val taskWithoutTimeout2 = taskWithoutTimeout.toNewTask()

        instance.submit(taskWithTimeout)
        instance.submit(taskWithoutTimeout)
        instance.submit(taskWithoutTimeout2)

        val finishedTasks = instance.tasks.first { tasks ->
            tasks.all { it.taskState.executionState == TaskState.ExecutionState.FINISHED }
        }
        instance.tasks.first().size shouldBe 3

        finishedTasks.single { it.taskState.request == taskWithTimeout }.apply {
            taskState.isFailed shouldBe true
            taskState.error shouldBe instanceOf(TimeoutCancellationException::class)
        }
        finishedTasks.single { it.taskState.request == taskWithoutTimeout }.apply {
            taskState.isSuccessful shouldBe true
            taskState.error shouldBe null
            taskState.result shouldNotBe null
        }
        finishedTasks.single { it.taskState.request == taskWithoutTimeout2 }.apply {
            taskState.isSuccessful shouldBe true
            taskState.error shouldBe null
            taskState.result shouldNotBe null
        }

        instance.close()
    }

    @Test
    fun `parallel tasks can timeout`() = runTest2 {
        val instance = createInstance(scope = this)

        val task1WithTimeout = DefaultTaskRequest(
            type = TimeoutTask::class,
            arguments = TimeoutTaskArguments()
        )
        val task2WithTimeout = DefaultTaskRequest(
            type = TimeoutTask2::class,
            arguments = TimeoutTaskArguments()
        )
        val task1WithoutTimeout = DefaultTaskRequest(
            type = TimeoutTask::class,
            arguments = TimeoutTaskArguments(delay = 5000)
        )
        val task2WithoutTimeout = DefaultTaskRequest(
            type = TimeoutTask2::class,
            arguments = TimeoutTaskArguments(delay = 5000)
        )

        instance.submit(task1WithTimeout)
        instance.submit(task2WithTimeout)
        instance.submit(task1WithoutTimeout)
        instance.submit(task2WithoutTimeout)

        val finishedTasks = instance.tasks.first { tasks ->
            tasks.all { it.taskState.executionState == TaskState.ExecutionState.FINISHED }
        }
        instance.tasks.first().size shouldBe 4

        finishedTasks.single { it.taskState.request == task1WithTimeout }.apply {
            taskState.isFailed shouldBe true
            taskState.error shouldBe instanceOf(TimeoutCancellationException::class)
        }
        finishedTasks.single { it.taskState.request == task2WithTimeout }.apply {
            taskState.isFailed shouldBe true
            taskState.error shouldBe instanceOf(TimeoutCancellationException::class)
        }
        finishedTasks.single { it.taskState.request == task1WithoutTimeout }.apply {
            taskState.isSuccessful shouldBe true
            taskState.error shouldBe null
            taskState.result shouldNotBe null
        }
        finishedTasks.single { it.taskState.request == task2WithoutTimeout }.apply {
            taskState.isSuccessful shouldBe true
            taskState.error shouldBe null
            taskState.result shouldNotBe null
        }

        instance.close()
    }

    @Test
    fun `old tasks are pruned from history`() = runTest2 {
        val instance = createInstance(scope = this)

        val expectedFiles = mutableListOf<File>()

        repeat(100) {
            val arguments = QueueingTask.Arguments(
                delay = 5,
                values = listOf("TestText"),
                path = File(testDir, UUID.randomUUID().toString())
            )
            expectedFiles.add(arguments.path)

            val request = DefaultTaskRequest(type = QueueingTask::class, arguments = arguments)
            instance.submit(request)
            delay(5)
        }

        this.advanceUntilIdle()

        expectedFiles.forEach {
            it.exists() shouldBe true
        }

        val taskHistory = instance.tasks.first()
        taskHistory.size shouldBe 50
        expectedFiles.size shouldBe 100

        val sortedHistory = taskHistory.sortedBy { it.taskState.startedAt }.apply {
            first().taskState.startedAt!!.isBefore(last().taskState.startedAt) shouldBe true
        }

        expectedFiles.subList(50, 100) shouldBe sortedHistory.map {
            (it.taskState.request.arguments as QueueingTask.Arguments).path
        }

        instance.close()
    }

    @Test
    fun `silent error handling`() = runTest2 {

        val error: Throwable = spyk(Throwable())

        mockkStatic("de.rki.coronawarnapp.exception.reporting.ExceptionReporterKt")
        mockkStatic("de.rki.coronawarnapp.bugreporting.BugReporterKt")

        every { error.report(any(), any(), any()) } just Runs
        every { error.reportProblem(any()) } just Runs

        runWithoutChildExceptionCancellation {
            val instance = createInstance(scope = this)

            val request =
                DefaultTaskRequest(type = SilentErrorTask::class, arguments = SilentErrorTask.Arguments(error = error))
            instance.submit(request)

            val infoFinished = instance.tasks
                .first { it.single().taskState.executionState == TaskState.ExecutionState.FINISHED }
                .single()

            infoFinished.apply {
                taskState.error shouldNotBe null
                verify(exactly = 0) { any<Throwable>().report(any()) }
                verify(exactly = 1) { any<Throwable>().reportProblem(any()) }
            }

            instance.close()
        }
    }

    @Test
    fun `alert error handling`() = runTest2 {

        val error: Throwable = spyk(Throwable())

        mockkStatic("de.rki.coronawarnapp.exception.reporting.ExceptionReporterKt")
        mockkStatic("de.rki.coronawarnapp.bugreporting.BugReporterKt")

        every { error.report(any(), any(), any()) } just Runs
        every { error.reportProblem(any()) } just Runs

        runWithoutChildExceptionCancellation {
            val instance = createInstance(scope = this)

            val request =
                DefaultTaskRequest(type = AlertErrorTask::class, arguments = AlertErrorTask.Arguments(error = error))
            instance.submit(request)

            val infoFinished = instance.tasks
                .first { it.single().taskState.executionState == TaskState.ExecutionState.FINISHED }
                .single()

            infoFinished.apply {
                taskState.error shouldNotBe null
                verify(exactly = 1) { any<Throwable>().report(any()) }
                verify(exactly = 1) { any<Throwable>().reportProblem(any()) }
            }

            instance.close()
        }
    }
}
