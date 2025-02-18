/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.buildtree

import org.gradle.api.internal.GradleInternal
import org.gradle.composite.internal.IncludedBuildControllers
import org.gradle.initialization.exception.ExceptionAnalyser
import org.gradle.internal.build.BuildLifecycleController
import org.gradle.test.fixtures.work.TestWorkerLeaseService
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Function

class DefaultBuildTreeLifecycleControllerTest extends Specification {
    def gradle = Mock(GradleInternal)
    def buildController = Mock(BuildLifecycleController)
    def workerLeaseService = new TestWorkerLeaseService()
    def workController = Mock(BuildTreeWorkExecutor)
    def includedBuildController = Mock(IncludedBuildControllers)
    def exceptionAnalyzer = Mock(ExceptionAnalyser)
    def controller = new DefaultBuildTreeLifecycleController(buildController, workerLeaseService, workController, includedBuildController, exceptionAnalyzer)
    def reportableFailure = new RuntimeException()

    def setup() {
        buildController.gradle >> gradle
    }

    def "runs action after running tasks"() {
        def action = Mock(Function)

        when:
        def result = controller.fromBuildModel(true, action)

        then:
        result == "result"

        and:
        1 * buildController.scheduleRequestedTasks()
        1 * workController.execute(_)

        and:
        1 * action.apply(gradle) >> "result"

        and:
        1 * includedBuildController.finishBuild(_)
        1 * buildController.finishBuild(null, _)
    }

    def "does not run action if task execution fails"() {
        def action = Mock(Function)
        def failure = new RuntimeException()

        when:
        controller.fromBuildModel(true, action)

        then:
        def e = thrown(RuntimeException)
        e == reportableFailure

        and:
        1 * buildController.scheduleRequestedTasks()
        1 * workController.execute(_) >> { Consumer consumer -> consumer.accept(failure) }
        0 * action._

        and:
        1 * includedBuildController.finishBuild(_)
        1 * buildController.finishBuild(_, _)
        _ * exceptionAnalyzer.transform([failure]) >> reportableFailure
    }

    def "runs action after configuring build model"() {
        def action = Mock(Function)

        when:
        def result = controller.fromBuildModel(false, action)

        then:
        result == "result"

        and:
        1 * buildController.configuredBuild >> gradle

        and:
        1 * action.apply(gradle) >> "result"

        and:
        1 * includedBuildController.finishBuild(_)
        1 * buildController.finishBuild(null, _)
    }

    def "does not run action if configuration fails"() {
        def action = Mock(Function)
        def failure = new RuntimeException()

        when:
        controller.fromBuildModel(false, action)

        then:
        def e = thrown(RuntimeException)
        e == reportableFailure

        and:
        1 * buildController.configuredBuild >> { throw failure }
        0 * action._

        and:
        1 * includedBuildController.finishBuild(_)
        1 * buildController.finishBuild(_, _)
        _ * exceptionAnalyzer.transform([failure]) >> reportableFailure
    }
}
