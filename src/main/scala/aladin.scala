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
import javafx.scene.layout.HBox
import javafx.stage.Stage
import javafx.concurrent.{Service, Task}
import scala.concurrent._
import ExecutionContext.Implicits.global

// Barcode related imports
import com.google.zxing.{MultiFormatReader, RGBLuminanceSource, BinaryBitmap}
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.{DecodeHintType, BarcodeFormat}
import collection.JavaConverters._
import javafx.embed.swing.SwingFXUtils


// HTTP related imports
import com.stackmob.newman._
import com.stackmob.newman.dsl._
import java.net.{URL, URLEncoder}
import org.jsoup.Jsoup
import java.nio.charset.charset

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
            val result = reader.decode(bitmap, decodeHints)
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
  val decodeHints = collection.mutable.Map(DecodeHintType.POSSIBLE_FORMATS -> collection.mutable.Seq(BarcodeFormat.EAN_13).asJava).asJava
}

object AladinQuery {
  /* query form
  <form name="SearchForm" method="post">
  <input value="1" name="ActionType">
  <input value="DB" name="SearchEngineType">
  <input checked="checked" value="1" name="BranchType">
    ... radiobuttons
    1: 국내도서
    2: 음반
    3: DVD
    7: 외국도서
  <input type="text" name="KeyISBN">

  val r = POST(aladin).addHeaders("ActionType"->"1", "SearchEngineType"->"DB", "BranchType"->"1", "KeyISBN"->"9791185014456").apply
  */
  implicit val httpClient = new ApacheHttpClient

  val aladin = new URL("http://used.aladin.co.kr/shop/usedshop/wc2b_gate.aspx")

  // result
  val containerId = "pnItemList"  // 없으면 "pnNoItem" div 가짐
  val info = "#searchResult tbody tr:eq(0) td:eq(2) table:eq(0) tbody:eq(0) tr td a"
  // Jsoup의 xpath는 []대신 :eq()를 인덱스로 사용
  val pay = "#searchResult tbody tr:eq(0) td:eq(2) table:eq(1) tbody:eq(0) tr:eq(3) td"
  // [0] : 정가, [1] : 최상, [2] : 상, [3] : 중

  def query(isbn:String):Future[response.HttpResponse] = {
    val form = new Form(Map("ActionType"->"1",
        "SearchEngineType"->"DB", "BranchType"->"1", "KeyISBN"->isbn))
    POST(aladin).addHeaders(form.headers).addBody(form.body).apply
  }
  // val doc = Jsoup.parse(r.value.get.get.bodyString(Charset.forName("euc-kr")))
}

class Form(args:Map[String, String]) {
  val body = args.toList.map(t => s"${t._1}=${URLEncoder.encode(t._2)}").mkString("&")
  def headers = Headers("Accept-Charset"->"utf-8",
                        "Content-Type"->"application/x-www-form-urlencoded",
                        "Content-Length"->body.size.toString)
}