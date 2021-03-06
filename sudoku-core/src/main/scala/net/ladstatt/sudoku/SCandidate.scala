package net.ladstatt.sudoku

import net.ladstatt.opencv.{Debug, OpenCV}
import org.opencv.core._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


object SCandidate {

  // todo remove
  def apply(nr: Int, frame: Mat, pipeline: FramePipeline, state: SudokuState, params: SParams = SParams()): SCandidate = {
    Try {
      new SCandidate(nr, pipeline, SRectangle(pipeline.frame, pipeline.detectRectangle.get, pipeline.corners), state)
    } match {
      case Success(x) => x
      case Failure(e) => {
        throw e
      }
    }
  }

}

trait SResult

/**
  *
  * @param nr number of the frame
  */
case class SCandidate(nr: Int,
                      pipeline: FramePipeline,
                      sRectangle: SRectangle,
                      oldState: SudokuState) extends SResult {

  if (Debug.Active) Debug.writeDebug(this)

  /**
    * This function uses an input image and a detection method to calculate the sudoku.
    */
  lazy val calc: Future[(SudokuResult, SudokuState)] = {
    val currentState = oldState.merge(sRectangle)
    for {
      solvedState <- Future(currentState.solve())
      withSolution <- Future(sRectangle.paintSolution(solvedState.someCells, currentState.library))
      annotatedSolution <- Future(SudokuUtils.paintCorners(withSolution, sRectangle.cellRois, solvedState.someCells, currentState.hitCounts, oldState.cap))
      warped = OpenCV.warp(annotatedSolution, pipeline.corners, sRectangle.detectedCorners)
      solutionMat <- Future(OpenCV.copySrcToDestWithMask(warped, pipeline.frame, warped)) // copy solution mat to input mat
    } yield {
      (SSuccess(this, sRectangle, solvedState.someResult.map(s => SolutionFrame(s, solutionMat))), currentState)
    }
  }

}
