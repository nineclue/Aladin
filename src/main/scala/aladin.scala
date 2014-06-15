import java.io.{File, ByteArrayInputStream}
import scala.collection.JavaConversions._
import org.opencv.core.{Mat, CvType, MatOfByte, Rect}
import org.opencv.highgui.{Highgui, VideoCapture}
import org.opencv.imgproc.Imgproc
import java.util.Date
import java.util.UUID
import javafx.application.{Application, Platform}
import javafx.event.Event
import javafx.event.EventHandler
import javafx.scene.{Scene, Group}
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.{HBox, VBox}
import javafx.scene.control.{TableView, TableColumn, Label}
import javafx.scene.control.cell.PropertyValueFactory
import javafx.beans.property.{SimpleStringProperty, SimpleIntegerProperty, SimpleBooleanProperty}
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
import org.jsoup.nodes.Document
import java.nio.charset.Charset

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

  class Query extends Service[Future[Document]] {
    def createTask = 
      if (!isbn.isEmpty) 
        mkTask({
          AladinQuery.query(isbn.get).map(resp =>
              Jsoup.parse(resp.bodyString(Charset.forName("euc-kr"))))
        }) 
      else 
        mkTask(Future.successful(new Document("")))
  }

  def mkTask[X](callFn: => X): Task[X] = new Task[X] { override def call(): X = callFn }
  def mkEventHandler[E <: Event](f: E => Unit) = new EventHandler[E] { def handle(e: E) = f(e) }

  class UsedBook(title:String, author:String, publisher:String, high:Int, middle:Int, low:Int) {
    val i = new SimpleBooleanProperty(true)
    def includeProperty = i
    def getInclude:Boolean = i.get
    def setInclude(v:Boolean) = i.set(v)

    val t = new SimpleStringProperty(title)
    def titleProperty = t
    def getTitle:String = t.get
    def setTitle(v:String) = t.set(v)

    val a = new SimpleStringProperty(author)
    def authorProperty = a
    def getAuthor:String = a.get
    def setAuthor(v:String) = a.set(v)

    val p = new SimpleStringProperty(publisher)
    def publisherProperty = p
    def getPublisher:String = p.get
    def setPublisher(v:String) = p.set(v)

    val h = new SimpleIntegerProperty(high)
    def highProperty = h
    def getHigh:Int = h.get
    def setHigh(v:Int) = h.set(v)

    val m = new SimpleIntegerProperty(middle)
    def middleProperty = m
    def getMiddle = m.get
    def setMiddle(v:Int) = m.set(v)

    val l = new SimpleIntegerProperty(low)
    def lowProperty = l
    def getLow = l.get
    def setLow(v:Int) = l.set(v)
  }

  val bookData = javafx.collections.FXCollections.observableArrayList[UsedBook]()

  def resultTable():TableView[UsedBook] = {
    val t = new TableView[UsedBook]
    t.setItems(bookData)
    val col0 = new TableColumn[UsedBook, Boolean]("포함")
    col0.setCellValueFactory(new PropertyValueFactory[UsedBook, Boolean]("include"))
    val col1 = new TableColumn[UsedBook, String]("제 목")
    col1.setMinWidth(200)
    col1.setCellValueFactory(new PropertyValueFactory[UsedBook, String]("title"))
    val col2 = new TableColumn[UsedBook, String]("저 자")
    col2.setCellValueFactory(new PropertyValueFactory[UsedBook, String]("author"))
    val col3 = new TableColumn[UsedBook, String]("출판사")
    col3.setCellValueFactory(new PropertyValueFactory[UsedBook, String]("publisher"))
    val col4 = new TableColumn[UsedBook, Int]("최 상")
    col4.setCellValueFactory(new PropertyValueFactory[UsedBook, Int]("high"))
    val col5 = new TableColumn[UsedBook, Int]("상")
    col5.setCellValueFactory(new PropertyValueFactory[UsedBook, Int]("middle"))
    val col6 = new TableColumn[UsedBook, Int]("중")
    col6.setCellValueFactory(new PropertyValueFactory[UsedBook, Int]("low"))
    t.getColumns.addAll(col0, col1, col2, col3, col4, col5, col6)
    t
  }

  val cam = new Webcam
  val query = new Query
  var isbn:Option[String] = None

  override def start(stage: Stage):Unit = {
    stage.setTitle("알라딘 중고 서적 검사")
    val videoView = new ImageView()
    val root = new HBox()
    val result = resultTable
    root.getChildren.addAll(videoView, result)
    val scene = new Scene(root, 1300, 700)
    stage.setScene(scene)
    cam.setOnSucceeded(
      mkEventHandler(event => {
        for {
          omat <- event.getSource.getValue.asInstanceOf[Future[Mat]]
          mat = new Mat(omat, cropRoi)
        } {
          val gmat = new Mat
          Imgproc.cvtColor(mat, gmat, Imgproc.COLOR_BGR2GRAY)
          val png = new MatOfByte()
          Highgui.imencode(".png", gmat, png)
          val im = new Image(new ByteArrayInputStream(png.toArray()))
          videoView.setImage(im)

          val bim = SwingFXUtils.fromFXImage(im, null)
          val lsource = new BufferedImageLuminanceSource(bim)
          val bitmap = new BinaryBitmap(new HybridBinarizer(lsource))
          isbn = try {
            // val result = reader.decode(bitmap)
            val result = reader.decode(bitmap, decodeHints)
            // decodeHints 빼면 QR 포함 다른 코드들도 인식
            println(result.getText)
            val code = result.getText
            if (!isbns.contains(code)) {
              isbns += code
              Some(code)
            } else {
              None
            }
          } catch {
            case e:com.google.zxing.NotFoundException =>
              // no barcodes in image, just ignore
              None
          }
          if (bookData.isEmpty) {
            println("adding...")
            bookData.add(new UsedBook("실마리의 마음", "허서구", "문학동네", 10000, 7000, 5000))
          }
          Platform.runLater(
            new Runnable() {
              def run = cam.restart
              if (!isbn.isEmpty) query.restart
            }
          )
        }})
    )
    query.setOnSucceeded(
      mkEventHandler(event => {
        for (
          doc <- event.getSource.getValue.asInstanceOf[Future[Document]]
        ) {
          println("query handler")
          // val doc = Jsoup.parse(resp.bodyString(Charset.forName("euc-kr")))
          val bookinfo = doc.select(AladinQuery.info)
          val pay = doc.select(AladinQuery.pay)
          // bookData.add(new UsedBook(bookinfo[0], bookinfo[1], bookinfo[2], pay[0], pay[1], pay[2]))
        }
      })
    )
    cam.start
    query.start
    stage.show()
  }

  val reader = new MultiFormatReader()
  val cropRoi = new Rect(0, 0, 700, 700)
  val decodeHints = collection.mutable.Map(DecodeHintType.POSSIBLE_FORMATS -> collection.mutable.Seq(BarcodeFormat.EAN_13).asJava).asJava

  val isbns = collection.mutable.Set.empty[String]
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