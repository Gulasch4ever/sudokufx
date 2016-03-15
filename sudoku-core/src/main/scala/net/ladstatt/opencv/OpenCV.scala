package net.ladstatt.opencv

import java.io.File

import net.ladstatt.core.{CanLog, SystemEnv}
import net.ladstatt.sudoku._
import org.opencv.core._
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

/**
  * various opencv related stuff
  */
object OpenCV extends CanLog {

  import Parameters._

  lazy val EmptyCorners = new MatOfPoint2f

  lazy val Kernel: Mat = {
    val k = new Mat(3, 3, CvType.CV_8U)
    k.put(0, 0, Array[Byte](0, 1, 0, 1, 1, 1, 0, 1, 0))
    k
  }

  def copyMat(orig: Mat): Mat = {
    val dest = new Mat()
    orig.copyTo(dest)
    dest
  }

  def copyTo(data: Mat, canvas: Mat, roi: Rect): Unit = {
    val cellTarget = new Mat(canvas, roi)
    data.copyTo(cellTarget)
  }

  def paintRect(canvas: Mat, rect: Rect, color: Scalar, thickness: Int): Unit = {
    Imgproc.rectangle(canvas, rect.tl(), rect.br(), color, thickness)
  }

  def extractCurveWithMaxArea(curveList: Seq[MatOfPoint]): Option[(Double, MatOfPoint)] = {
    val curvesWithAreas: Seq[(Double, MatOfPoint)] =
      for (curve <- curveList) yield (Imgproc.contourArea(curve), curve)
    curvesWithAreas.sortWith((a, b) => a._1 > b._1).headOption
  }


  /**
    * (a,b,c,d)
    *
    * @param corners
    * @return
    */
  def isSomewhatSquare(corners: Seq[Point]): Boolean = {

    import scala.math.{abs, atan2}

    def calcAngle(a: Point, b: Point) = {
      atan2(b.y - a.y, b.x - a.x) * 180 / scala.math.Pi
    }

    if (corners.nonEmpty) {
      val hasAlignedAngles: Boolean =
        abs(calcAngle(corners(0), corners(1)) - calcAngle(corners(3), corners(2))) < 10 &&
          abs(calcAngle(corners(0), corners(3)) - calcAngle(corners(1), corners(2))) < 10

      hasAlignedAngles
    } else {
      false
    }
  }

  def mkCorners(size: Size): MatOfPoint2f = {
    val (width, height) = (size.width, size.height)
    new MatOfPoint2f(new Point(0, 0),
      new Point(width, 0),
      new Point(width, height),
      new Point(0, height))
  }


  def toMat(buffer: Array[Byte], size: Size): Mat = {
    val m = new Mat(size, CvType.CV_8UC1)
    m.put(0, 0, buffer)
    m
  }

  def persist(mat: Mat, file: File): Try[File] =
    Try {
      logWithTimer(s"Wrote ${file.getAbsolutePath}", {
        if (!Imgcodecs.imwrite(file.getAbsolutePath, mat)) {
          throw new RuntimeException(s"Could not save to file $file")
        } else {
          file
        }
      })
    }


  def alphaBlend(src: Mat, alpha: Mat): Mat = {
    val channels = new java.util.ArrayList[Mat]()
    Core.split(src, channels)
    channels.add(alpha)
    val merged = new Mat
    Core.merge(channels, merged)
    merged
  }

  def mkMatWithCurve(image: Mat, curve: MatOfPoint2f, color: Scalar, thickness: Int): Future[Mat] = {
    mkMatWithCurve(image, curve.toList.toList, color, thickness)
  }

  def mkMatWithCurve(image: Mat, points: List[Point], color: Scalar, thickness: Int): Future[Mat] =
    Future {
      if (points.size > 2) {
        for (linePoints <- points.sliding(2)) {
          Imgproc.line(image, linePoints(0), linePoints(1), color, thickness)
        }
      }
      image
    }

  /**
    * wraps equalizeHist from Imgproc
    *
    * @param input
    * @return
    */
  def equalizeHist(input: Mat): Future[Mat] =
    Future {
      val output = new Mat
      Imgproc.equalizeHist(input, output)
      output
    }


  def norm(mat: Mat): Future[Mat] = {
    for {
      b <- gaussianblur(mat)
      dilated <- dilate(b, OpenCV.Kernel)
      thresholded <- adaptiveThreshold(dilated, 255, 9)
    } yield thresholded
  }

  /**
    * Returns position and value for a template for a given image
    *
    * @return
    */
  def matchTemplate(candidate: Mat, number: Int, withNeedle: Mat): Future[(Int, Double)] = {

    val normedCandidateF = norm(candidate)
    val normedNeedleF = norm(withNeedle)

    val result =
      for {
        c <- normedCandidateF
        needle <- normedNeedleF
      }
        yield {
          val width = candidate.cols - withNeedle.cols + 1
          val height = candidate.rows - withNeedle.rows + 1
          val resultImage = new Mat(width, height, CvType.CV_32FC1)
          Imgproc.matchTemplate(c, needle, resultImage, Imgproc.TM_SQDIFF)
          val minMaxResult = Core.minMaxLoc(resultImage)
          //        OpenCV.persist(c, new File(s"target/${number}_${minMaxResult.minVal}_candidate_.png"))
          //        OpenCV.persist(needle, new File(s"target/${number}_${minMaxResult.minVal}_needle_.png"))
          (number, minMaxResult.minVal)
        }
    result
  }

  def resize(s: Mat, size: Size): Mat = {
    val dest = new Mat()
    Imgproc.resize(s, dest, size)
    dest
  }

  def resizeFuture(source: Mat, size: Size): Future[Mat] = Future(resize(source, size))


  /**
    * copies source to destination Mat with given mask and returns the destination mat.
    *
    * @param source
    * @param destination
    * @param pattern
    * @return
    */
  def copySrcToDestWithMask(source: Mat, destination: Mat, pattern: Mat): Future[Mat] =
    Future {
      source.copyTo(destination, pattern)
      destination
    }

  /**
    * warps image to make feature extraction's life easier (time intensive call)
    */
  def warp(input: Mat, srcCorners: MatOfPoint2f, destCorners: MatOfPoint2f): Mat = {
    require(srcCorners.toList.size == 4)
    require(destCorners.toList.size == 4)

    val transformationMatrix = Imgproc.getPerspectiveTransform(srcCorners, destCorners)

    val dest = new Mat()
    Imgproc.warpPerspective(input, dest, transformationMatrix, input.size())
    dest
  }

  // input mat will be altered by the findContours(...) function
  def findContours(input: Mat, mode: Int , method: Int): Seq[MatOfPoint] = {
    val contours = new java.util.ArrayList[MatOfPoint]()
    Imgproc.findContours(input, contours, new Mat, mode, method)
    contours
  }

  def has4Sides(needle: MatOfPoint2f) = needle.size == new Size(1, 4)

  def mkApproximation(curve: MatOfPoint2f, epsilon: Double = 0.02): MatOfPoint2f = {
    val arcLength = Imgproc.arcLength(curve, true)
    val approxCurve = new MatOfPoint2f
    Imgproc.approxPolyDP(curve, approxCurve, epsilon * arcLength, true)
    approxCurve
  }

  def findBestFit(contours: Seq[MatOfPoint],
                  center: Point,
                  minArea: Double,
                  maxArea: Double): Option[(Double, MatOfPoint2f, MatOfPoint)] = {
    val candidates =
      for (c <- contours if {
        val boundingRect = Imgproc.boundingRect(c)
        val area = boundingRect.area
        (minArea < area) && (area < maxArea) && boundingRect.contains(center)
      }) yield {
        val curve = new MatOfPoint2f
        curve.fromArray(c.toList: _*)
        val contourArea = Imgproc.contourArea(curve)
        (contourArea, curve, c)
      }
    candidates.sortWith((a, b) => a._1 > b._1).headOption
  }

  def findCellContour(original: Mat,
                      center: Point,
                      minArea: Double,
                      maxArea: Double): Option[Mat] = {
    val input = new Mat
    original.copyTo(input)
    val contours = findContours(input, Imgproc.RETR_TREE,Imgproc.CHAIN_APPROX_SIMPLE)
    findBestFit(contours, center, minArea, maxArea) map {
      case (contourArea, curve, contour) => {
        original.submat(Imgproc.boundingRect(contour))
      }
    }
  }

  /**
    * sort points in following order:
    * topleft, topright, bottomright, bottomleft
    */
  def mkSortedCorners(points: MatOfPoint2f): Seq[Point] = {
    val pointsAsList = points.toList
    val sortBySum = pointsAsList.sortWith((l, r) => (l.x + l.y) < (r.x + r.y))
    val sortByDifference = pointsAsList.sortWith((l, r) => (l.y - l.x) < (r.y - r.x))
    val (topleft, bottomright) = (sortBySum.head, sortBySum.reverse.head)
    val (topright, bottomleft) = (sortByDifference.head, sortByDifference.reverse.head)
    Seq(topleft, topright, bottomright, bottomleft)
  }

  def adaptiveThreshold(input: Mat,
                        maxValue: Double = 255,
                        blockSize: Int = 5,
                        c: Double = 2,
                        adaptiveMethod: Int = Imgproc.ADAPTIVE_THRESH_MEAN_C): Future[Mat] =
    Future {
      val thresholded = new Mat()
      Imgproc.adaptiveThreshold(input, thresholded, maxValue, adaptiveMethod, Imgproc.THRESH_BINARY, blockSize, c)
      thresholded
    }

  def threshold(input: Mat): Future[Mat] =
    Future {
      val output = new Mat
      Imgproc.threshold(input, output, 30, 255, Imgproc.THRESH_BINARY)
      output
    }

  def bitwiseNot(input: Mat): Future[Mat] =
    Future {
      val output = new Mat
      Core.bitwise_not(input, output)
      output
    }


  def dilate(input: Mat, kernel: Mat): Future[Mat] =
    Future {
      val output = new Mat
      val anchor = new Point(-1, -1)
      Imgproc.dilate(input, output, kernel, anchor, 2)
      output
    }


  def erode(input: Mat): Future[Mat] =
    Future {
      val output = new Mat
      val ersize = 0.0
      val m = Imgproc.getStructuringElement(Imgproc.MORPH_CROSS,
        new Size(2 * ersize + 1, 2 * ersize + 1),
        new Point(ersize, ersize))
      Imgproc.erode(input, output, m)
      //Imgproc.erode(input, output, mkKernel(3, ArrayBuffer[Byte](0, 1, 0, 1, 1, 1, 0, 1, 0)))
      output
    }


  def gaussianblur(input: Mat): Future[Mat] =
    Future {
      val dest = new Mat()
      Imgproc.GaussianBlur(input, dest, new Size(11, 11), 0)
      dest
    }

  def blur(input: Mat): Future[Mat] =
    Future {
      val dest = new Mat()
      Imgproc.blur(input, dest, new Size(20, 20), new Point(-1, -1))
      dest
    }


  def runtimeNativeLibName =
    if (SystemEnv.runOnMac)
      "../lib/libopencv_java310.so"
    else if (SystemEnv.isX64) {
      "lib/win/x64/opencv_java246.dll"
    } else {
      "lib/win/x86/opencv_java246.dll"
    }

  def loadNativeLib(nativeLibName: => String = runtimeNativeLibName) = {
    val nativeLib = new File(nativeLibName)
    assert(nativeLib.exists, "Could not find %s.".format(nativeLibName))
    System.load(nativeLib.getAbsolutePath())
  }

  def filter2D(kernel: Mat)(input: Mat): Mat = {
    val out = new Mat
    Imgproc.filter2D(input, out, -1, kernel)
    out
  }


  /**
    * converts the input mat to another color space
    *
    * @param input
    * @return
    */
  def toGray(input: Mat): Future[Mat] =
    Future {
      val grayed = new Mat
      Imgproc.cvtColor(input, grayed, Imgproc.COLOR_BGR2GRAY)
      grayed
    }


  // only search for contours in a subrange of the original cell to get rid of possible border lines
  // TODO: remove, likely not really necessary
  def specialize(cellRawData: Mat): Future[(Mat, Point, Double, Double)] =
    Future {
      val (width, height) = (cellRawData.size.width, cellRawData.size.height)
      val cellData = new Mat(cellRawData, new Range((height * 0.1).toInt, (height * 0.9).toInt), new Range((width * 0.1).toInt, (width * 0.9).toInt))
      val cellArea = cellData.size().area
      val (minArea, maxArea) = (0.15 * cellArea, 0.5 * cellArea)
      val (centerX, centerY) = (cellData.size.width / 2, cellData.size.height / 2)
      (cellData, new Point(centerX, centerY), minArea, maxArea)
    }

  def preprocess2(input: Mat): Future[Mat] = {
    for {
      equalized <- equalizeHist(input)
      blurred <- gaussianblur(equalized)
      thresholded <- threshold(blurred)
      inverted <- bitwiseNot(thresholded)
    } yield inverted
  }

  def detectCell(detectNumber: Mat => Future[(Int, SHitQuality)], sudokuCanvas: Mat, roi: Rect): Future[SCell] = {
    for {
      contour <- extractContour(sudokuCanvas.submat(roi))
      (value, quality) <- contour.map(detectNumber).getOrElse(Future.successful((0, 0.0)))
    } yield {
      SCell(value, quality, roi)
    }
  }

  // filter out false positives
  // use information known (size, position of digits)
  // the bounding box of the contours must fit into some rough predicate, like follows:
  // the area must be of a certain size
  // the area must not be greater than a certain size
  // the center of the image has to be part of the bounding rectangle
  def extractContour(coloredCell: Mat): Future[Option[Mat]] = {
    for {
      cell <- toGray(coloredCell)
      (cellData, center, minArea, maxArea) <- specialize(cell)
      a <- preprocess2(cellData)
    } yield
      findCellContour(a, center, minArea, maxArea)
  }

  def mkCellSize(sudokuSize: Size): Size = new Size(sudokuSize.width / ssize, sudokuSize.height / ssize)

  /*
  def imageIOChain(frame: Mat): Future[FramePipeline] = {

    for {
      working <- copySrcToDestWithMask(frame, new Mat, frame)
      grayed <- toGray(working)
      blurred <- gaussianblur(grayed)
      thresholdApplied <- adaptiveThreshold(blurred)
      inverted <- bitwiseNot(thresholdApplied)
      dilated <- dilate(inverted)
      eroded <- erode(inverted)
    //  dilated <- dilate(thresholdApplied)
    //  inverted <- bitwiseNot(dilated)
    } yield FramePipeline(frame, working, grayed, blurred, thresholdApplied, inverted, dilated, eroded)
  }     */

}