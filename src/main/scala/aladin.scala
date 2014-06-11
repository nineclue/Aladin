import org.opencv.highgui.VideoCapture
import java.io.{File, ByteArrayInputStream}
import org.opencv.highgui.Highgui
import scala.collection.JavaConversions._
import org.opencv.core.{Mat, CvType, MatOfByte}
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

object AladinChecker {
  def main(args: Array[String]): Unit = {
    System.load(new File("/usr/local/Cellar/opencv/2.4.9/share/OpenCV/java/libopencv_java249.dylib").getAbsolutePath())
    Application.launch(classOf[AladinChecker], args: _*)
  }
}

class AladinChecker extends javafx.application.Application {
  class Webcam extends Service[Future[Image]] {
    val source = new VideoCapture(0)
    def grabImage:Future[Image] = {
      future {
        assert(source.isOpened())
        if (source.grab) {
          val image = new Mat()
          while (source.read(image)==false) {}
          val memory = new MatOfByte()
          try {
            Highgui.imencode(".png", image, memory)
            new Image(new ByteArrayInputStream(memory.toArray()))
          }
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
          im <- event.getSource.getValue.asInstanceOf[Future[Image]]
        } {
          videoView.setImage(im)
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

}