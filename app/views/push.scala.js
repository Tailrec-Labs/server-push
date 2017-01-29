@(channel: String, protocol: String, endpoint: String)(implicit r: RequestHeader)

var mode = "chat"
var rttArr = []
var pktReq = 0

var resultTable = $("#result-table").DataTable({
//  "dom": 'T<"clear">lfrtip',
  "dom": "<'row'<'col-sm-6'lf><'col-sm-6'<'pull-right'B>>>rtip",
  "bFilter": false,
  "buttons": ['copy', 'excel', 'csv', 'pdf']
})

function isShowResult() {
  return $("#show-result").is(":checked")
}
function isSendAllAtOnce() {
  return $("#send-all").is(":checked")
}
function setProgress(percent) {
  percent = Math.floor(percent)
  var progress = $("#progress")
  if(percent == 0 || percent == 100){
    progress.removeClass("active")
  }else if(percent > 0 && percent < 100){
    if(!progress.hasClass("active")) progress.addClass("active")
  }
  progress.css("width", percent + "%").attr("aria-valuenow", percent)
}

function newBenchmark(n) {
  rttArr = []
  pktReq = n
  setProgress(0)
  $("#result-alert").hide()
  resultTable.clear().draw()
}

function addBenchmarkResult(msg) {
  
  var rtt = Date.now() - msg.timestamp
  
  if(isShowResult()){
    var text = msg.body
    if(text.length > 32) {
      text = text.substr(0, 32) + "... [length = " + (text.length) + " chars]"
    }
    var pt = msg.processingTime
    var diff = rtt - pt
    var diffPercent = ((diff/rtt)*100).toFixed(2)
    var row = [escapeHTML(text), msg.label, msg.timestamp, msg.viaServer, 
               rtt, pt, diff, diffPercent + "%"]
    resultTable.row.add(row).draw(false)
  }
  
  rttArr.push(rtt)
  
  setProgress(Math.floor(rttArr.length * 100/pktReq))
  
  if(rttArr.length == pktReq){
    var sumRtt = 0
    for(i in rttArr){
      sumRtt += rttArr[i]
    }
    var avgRtt = sumRtt/rttArr.length
    var sumDev = 0
    for(i in rttArr){
      sumDev += Math.pow((rttArr[i] - avgRtt), 2) 
    }
    var stdDev = Math.sqrt(sumDev/rttArr.length)
    $("#result-alert").fadeIn()
    var summary = "Avg = " + avgRtt.toFixed(2) + " ms, Std.Dev. = " + stdDev.toFixed(2) + " ms"
    $("#result-summary").text(summary)
  }
}

function showChatMessage(msg) {
  var text = "<strong>" + escapeHTML(msg.username) + "</strong> says: \"" + escapeHTML(msg.body) 
    + "\" <strong>via server:</strong> <small><i>" + msg.viaServer + "</i></small>"
  $("#messages" ).prepend("<div>" + text + "</div>")
  $("a[href='#tab-msg']").tab("show");
}

function onReceive(json) {
  var msg = JSON.parse(json)
  if(mode == "chat"){
    showChatMessage(msg)
  }else if(mode == "benchmark"){
    addBenchmarkResult(msg)
    if(!isSendAllAtOnce()){
      var params = {
        channel: msg.channel,
        username: msg.username,
        n: msg.n - 1
      }
      if($.isNumeric(msg.label)){
        params.label = parseInt(msg.label) + 1
      }else{
        params.label = msg.label
      }
      if(params.n > 0){
        console.log(params)
        server.publish(params)
      }
    }
  }
}

if("@protocol" == "ws"){
  
  var WS = window["MozWebSocket"] ? MozWebSocket : WebSocket
  var socket = new WS("@endpoint")
  socket.onmessage = function(e) {
    onReceive(e.data)
  }
}else if("@protocol" == "sse"){
  if (!!window.EventSource) {
    var source = new EventSource("@endpoint");
    source.addEventListener("message", function(e) {
      onReceive(e.data)
    });
  } else {
    alert("Sorry! This browser doesn't support Server Sent Event (SSE)."); 
  }
}else if("@protocol" == "comet"){
  var msgChanged = function(data) {
    onReceive(data)
  }
}


