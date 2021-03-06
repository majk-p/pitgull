package io.pg

import cats.effect.IO
import io.pg.Prelude._
import io.pg.config.ProjectConfig
import io.pg.config.ProjectConfigReader
import io.pg.fakes.ProjectActionsStateFake
import io.pg.fakes.ProjectConfigReaderFake
import io.pg.gitlab.webhook.Project
import io.pg.gitlab.webhook.WebhookEvent
import io.pg.webhook.WebhookProcessor
import weaver.Expectations
import weaver.SimpleIOSuite
import io.pg.config.Rule
import io.pg.config.Matcher
import io.pg.config.Action
import io.pg.config.TextMatcher

object WebhookProcessorTest extends SimpleIOSuite {

  final case class Resources[F[_]](
    actions: ProjectActions[F],
    resolver: StateResolver[F],
    projectModifiers: ProjectActionsStateFake.State.Modifiers[F],
    projectConfigs: ProjectConfigReader[F],
    projectConfigModifiers: ProjectConfigReaderFake.State.Modifiers[F],
    process: WebhookEvent => F[Unit]
  )

  val mkResources =
    ProjectConfigReaderFake
      .refInstance[IO]
      .flatMap { implicit configReader =>
        ProjectActionsStateFake.refInstance[IO].map { implicit projects =>
          implicit val logger = io.odin.consoleLogger[IO]()

          Resources(
            actions = projects,
            resolver = projects,
            projectModifiers = projects,
            projectConfigs = configReader,
            projectConfigModifiers = configReader,
            process = WebhookProcessor.instance[IO]
          )
        }
      }
      .resource

  def testWithResources(name: String)(use: Resources[IO] => IO[Expectations]) =
    test(name)(mkResources.use(use))

  testWithResources("unknown project") { resources =>
    import resources._
    val projectId = 66L

    process(WebhookEvent(Project(projectId), "merge_request"))
      .attempt
      .map { result =>
        expect(result.isLeft)
      }
  }

  testWithResources("known project with no MRs") { resources =>
    import resources._
    val projectId = 66L

    projectConfigModifiers.register(projectId, ProjectConfig.empty) *>
      process(WebhookEvent(Project(projectId), "merge_request")).as(success)
  }

  testWithResources("known project with one mergeable MR and one non-mergeable MR") { resources =>
    import resources._
    val projectId = 66L

    val project = Project(projectId)

    val matchSuccessfulPipeline = 
      Rule("pipeline successful", Matcher.PipelineStatus("success"), Action.Merge)

    for {
      _              <- projectConfigModifiers.register(projectId, ProjectConfig(List(matchSuccessfulPipeline)))
      mergeRequestId <- projectModifiers.open(projectId, "anyone@example.com", None)
      _              <- projectModifiers.finishPipeline(projectId, mergeRequestId)
      freshMR        <- projectModifiers.open(projectId, "anyone@example.com", None)

      _                         <- process(WebhookEvent(project, "merge_request"))
      mergeRequestsAfterProcess <- resolver.resolve(project)
    } yield expect {
      mergeRequestsAfterProcess.map(_.mergeRequestIid) == List(freshMR)
    }
  }

  testWithResources("known project with one mergeable MR - matching by author") { resources =>
    import resources._
    val projectId = 66L

    val project = Project(projectId)

    val correctDomainRegex = ".*@example.com".r

    val matchAuthorEmailDomain = 
      Rule("pipeline successful", Matcher.Author(TextMatcher.Matches(correctDomainRegex)), Action.Merge)

    for {
      _              <- projectConfigModifiers.register(projectId, ProjectConfig(List(matchAuthorEmailDomain)))
      mergeRequestId <- projectModifiers.open(projectId, "anyone@example.com", None)
      _              <- projectModifiers.finishPipeline(projectId, mergeRequestId)
      _                         <- process(WebhookEvent(project, "merge_request"))
      mergeRequestsAfterProcess <- resolver.resolve(project)
    } yield expect {
      mergeRequestsAfterProcess.map(_.mergeRequestIid) == Nil
    }
  }

}
