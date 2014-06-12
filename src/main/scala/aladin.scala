import org.opencv.highgui.VideoCapture
import java.io.{File, ByteArrayInputStream}
import org.opencv.highgui.Highgui
import scala.collection.JavaConversions._
import org.opencv.core.{Mat, CvType, MatOfByte, MatOfInt, MatOfInt4}
import java.util.Date
import java.util.UUID
import javafx.application.{Application, Platform}
import javafx.event.Event
import javafx.event.EventHandler
import javafx.scene.{Scene, Group}
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.stage.Stage
import javafx.concurrent.{Service, Task}
import scala.concurrent._
import ExecutionContext.Implicits.global

import com.google.zxing.{MultiFormatReader, RGBLuminanceSource, BinaryBitmap}
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import javafx.embed.swing.SwingFXUtils

object AladinChecker {
  def main(args: Array[String]): Unit = {
    System.load(new File("/usr/local/Cellar/opencv/2.4.9/share/OpenCV/java/libopencv_java249.dylib").getAbsolutePath())
    Application.launch(classOf[AladinChecker], args: _*)
  }
}

class AladinChecker extends javafx.application.Application {
  class Webcam extends Service[Future[Mat]] {
    val source = new VideoCapture(0)
    def grabImage:Future[Mat] = {
      future {
        assert(source.isOpened())
        if (source.grab) {
          val image = new Mat()
          while (source.retrieve(image)==false) {}
          // println(s"Got Image depth : ${image.depth()} ( ${image.elemSize()} ) - ${CvType.CV_8U}")
          image
        } else
          throw new RuntimeException("캡춰할 수 없습니다.")
      }
    }
    def createTask = mkTask(grabImage)
  }
  val cam = new Webcam

  def mkTask[X](callFn: => X): Task[X] = new Task[X] { override def call(): X = callFn }
  def mkEventHandler[E <: Event](f: E => Unit) = new EventHandler[E] { def handle(e: E) = f(e) }

  override def start(stage: Stage):Unit = {
    stage.setTitle("알라딘 중고 서적 검사")
    val videoView = new ImageView()
    val root = new Group()
    root.getChildren.add(videoView)
    val scene = new Scene(root, 500, 500)
    stage.setScene(scene)
    cam.setOnSucceeded(
      mkEventHandler(event => {
        for {
          mat <- event.getSource.getValue.asInstanceOf[Future[Mat]]
        } {
          val png = new MatOfByte()
          Highgui.imencode(".png", mat, png)
          val im = new Image(new ByteArrayInputStream(png.toArray()))
          videoView.setImage(im)

          val bim = SwingFXUtils.fromFXImage(im, null)
          val lsource = new BufferedImageLuminanceSource(bim)
          val bitmap = new BinaryBitmap(new HybridBinarizer(lsource))
          try {
            val result = reader.decode(bitmap)
            println(result)
            } catch {
              case e:com.google.zxing.NotFoundException =>
                // no barcodes in image, just ignore
            }

          Platform.runLater(
            new Runnable() {
              def run = cam.restart
            }
          )
        }})
    )
    cam.start
    stage.show()
  }

  val reader = new MultiFormatReader()
}