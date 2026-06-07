package com.vito.gateway

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 內建瀏覽器（WebView）+ 自動化。對應桌面 gateway 的 browser_control。
 *
 * 設計重點（和桌面同思路）：
 * - snapshot 用 JS 掃描可互動元素（含 cursor:pointer 的 React div、同源 iframe），每個給 [eN] + 座標。
 * - 點擊/輸入用「在座標 elementFromPoint 派發完整事件序列」，對 React/SPA 有效；
 *   輸入用 React 相容的 native value setter + input/change 事件（受控元件才吃得到）。
 * - WebView 操作必須在主執行緒；server 在背景執行緒 → 用 Handler.post + CountDownLatch 同步取回結果。
 */
object BrowserController {
    @SuppressLint("StaticFieldLeak")
    @Volatile private var webView: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    fun isReady() = webView != null

    /**
     * 常駐 WebView（用 application context，不綁任何分頁）。第一次呼叫時在主執行緒建立並手動
     * measure/layout 到固定尺寸 → 即使不在前景分頁，DOM 仍有 layout、座標有效（背景也能操作）。
     */
    fun ensureWebView(ctx: Context): WebView {
        webView?.let { return it }
        val appCtx = ctx.applicationContext
        val latch = CountDownLatch(1)
        main.post {
            try {
                val w = WebView(appCtx)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.databaseEnabled = true
                w.settings.loadWithOverviewMode = true
                w.settings.useWideViewPort = true
                w.settings.mediaPlaybackRequiresUserGesture = false
                // 用乾淨的桌面 Chrome UA（去掉 "; wv" WebView 標記、不加自訂 token），
                // 降低被 Google 等網站當機器人攔截（Sorry/驗證頁）的機率。
                w.settings.userAgentString =
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                w.webChromeClient = android.webkit.WebChromeClient()
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(BrowserJs.HELPERS, null)
                    }
                    // 攔非 http(s) scheme：Google Maps 等會重導向 intent:// 想喚起 App，
                    // WebView 不認得 → net::ERR_UNKNOWN_URL_SCHEME。自動化要留在頁面內，
                    // 所以把 intent:// 還原成 https 繼續在 WebView 載入；其他外部 scheme 直接擋掉不當機。
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        val u = request?.url?.toString() ?: return false
                        if (u.startsWith("http://") || u.startsWith("https://")) return false
                        try {
                            if (u.startsWith("intent://")) {
                                val it = android.content.Intent.parseUri(u, android.content.Intent.URI_INTENT_SCHEME)
                                val fb = it.getStringExtra("browser_fallback_url")
                                val data = it.dataString
                                val target = when {
                                    !fb.isNullOrBlank() && fb.startsWith("http") -> fb
                                    data != null && data.startsWith("http") -> data
                                    else -> "https://" + u.substringAfter("intent://").substringBefore("#Intent")
                                }
                                view?.loadUrl(target)
                            }
                        } catch (_: Throwable) {}
                        return true   // intent://、market://、tel: 等一律不交給 WebView 載入（避免錯誤頁）
                    }
                }
                // 手動量測+佈局：背景（未 attach 到畫面）時也有尺寸 → getBoundingClientRect 座標有效
                val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
                val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                w.measure(wSpec, hSpec)
                w.layout(0, 0, 1080, 1920)
                w.loadUrl("https://www.google.com")
                webView = w
            } catch (e: Throwable) {
                GatewayState.log("WebView 建立失敗：${e.message}")
            }
            latch.countDown()
        }
        latch.await(8, TimeUnit.SECONDS)
        return webView ?: throw IllegalStateException("WebView 尚未就緒")
    }

    /** 在主執行緒同步執行 WebView 動作並取回字串結果（背景 server thread 呼叫）。 */
    private fun <T> onMain(timeoutSec: Long = 20, block: (WebView, (T) -> Unit) -> Unit): T? {
        val wv = webView ?: return null
        val latch = CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        var result: T? = null
        main.post {
            try {
                block(wv) { r -> result = r; latch.countDown() }
            } catch (e: Throwable) {
                latch.countDown()
            }
        }
        latch.await(timeoutSec, TimeUnit.SECONDS)
        return result
    }

    private fun evalJs(js: String, timeoutSec: Long = 12): String {
        val r = onMain<String>(timeoutSec) { wv, done ->
            wv.evaluateJavascript(js) { value -> done(value ?: "null") }
        }
        return unquote(r ?: "null")
    }

    // evaluateJavascript 回的是 JSON 字串（被引號包起來）→ 還原
    private fun unquote(s: String): String {
        return try {
            if (s.startsWith("\"")) JSONArray("[$s]").getString(0) else s
        } catch (e: Throwable) { s }
    }

    fun navigate(url0: String): String {
        var url = url0.trim()
        if (url.isNotEmpty() && !Regex("^(https?|about|data|file):").containsMatchIn(url)) url = "https://$url"
        onMain<Unit>(30) { wv, done -> wv.loadUrl(url); done(Unit) }
        // 等頁面 + SPA hydrate
        Thread.sleep(2500)
        return "已開啟 ${currentUrl()}\n\n" + snapshot()
    }

    fun currentUrl(): String = onMain<String>(5) { wv, done -> done(wv.url ?: "") } ?: ""

    fun back(): String {
        onMain<Unit>(10) { wv, done -> if (wv.canGoBack()) wv.goBack(); done(Unit) }
        Thread.sleep(1200)
        return "已返回 ${currentUrl()}\n\n" + snapshot()
    }

    /** 掃描可互動元素，回給 AI 的清單（含 [eN] 編號）。元素過少自動重抓（SPA 慢）。 */
    fun snapshot(): String {
        var json = "{}"
        for (attempt in 0 until 4) {
            // 短逾時：頁面載入時主執行緒被佔住會讓 callback 遲遲不回，與其卡滿不如快速放掉重試
            json = evalJs(JS_SNAPSHOT, 6)
            val n = try { JSONObject(json).getJSONArray("elements").length() } catch (e: Throwable) { 0 }
            if (n >= 5 || attempt == 3) break
            Thread.sleep(1500)
        }
        return formatSnapshot(json)
    }

    private fun formatSnapshot(json: String): String {
        return try {
            val o = JSONObject(json)
            val els = o.getJSONArray("elements")
            val sb = StringBuilder()
            sb.append("頁面：").append(o.optString("title")).append("\n")
            sb.append("網址：").append(o.optString("url")).append("\n")
            sb.append("可互動元素（共 ").append(els.length()).append(" 個）：\n")
            for (i in 0 until els.length()) {
                val e = els.getJSONObject(i)
                sb.append("  [").append(e.getString("ref")).append("] ").append(e.optString("role"))
                val type = e.optString("type")
                if (type.isNotEmpty()) sb.append("/").append(type)
                val nm = e.optString("name")
                if (nm.isNotEmpty()) sb.append(" \"").append(nm).append("\"")
                sb.append("\n")
            }
            sb.toString().take(5000)
        } catch (e: Throwable) { "（頁面解析失敗）\n網址：${currentUrl()}" }
    }

    fun click(target: String): String {
        val js = "window.__gwClick(" + JSONObject.quote(target) + ")"
        val r = evalJs(js)
        Thread.sleep(700)
        return if (r.startsWith("OK")) "已點擊「$target」\n\n" + snapshot()
        else "⚠️ 點擊「$target」失敗（$r）——請依下方當前畫面重選目標：\n\n" + snapshot()
    }

    fun type(target: String, text: String, submit: Boolean): String {
        val js = "window.__gwType(" + JSONObject.quote(target) + "," + JSONObject.quote(text) + "," + submit + ")"
        val r = evalJs(js)
        Thread.sleep(if (submit) 900 else 300)
        return if (r.startsWith("OK")) "已輸入「$text」${if (submit) "並送出" else ""}\n\n" + snapshot()
        else "⚠️ 輸入失敗（$r）——請依下方當前畫面重選輸入框：\n\n" + snapshot()
    }

    fun readText(): String {
        // 讀整頁文字值得多等一點（頁面可能還在載入），但仍要遠低於 server 端 90s 上限
        val t = evalJs("(function(){var t=document.body?document.body.innerText:'';return (t||'').replace(/\\s+/g,' ').trim().slice(0,6000);})()", 30)
        return "頁面：${currentUrl()}\n\n$t"
    }
}

// ── 注入 WebView 的自動化 JS（snapshot / click / type）────────────────────────
private const val JS_SNAPSHOT = """
(function(){
  try{
  document.querySelectorAll('[data-gwref]').forEach(function(e){e.removeAttribute('data-gwref');});
  var out=[],seen={},i=0;
  var sel='a,button,input,textarea,select,[role=button],[role=link],[role=tab],[role=checkbox],[role=menuitem],[role=combobox],[role=option],[role=textbox],[onclick],[contenteditable=true],[tabindex]';
  function collect(doc){
    var cand=[].slice.call(doc.querySelectorAll(sel));
    // cursor:pointer 補抓（給 React 把 div 當按鈕用的網站）。getComputedStyle 很貴，
    // 大頁面（如 Google 結果頁有上千 div/span）全掃會卡死主執行緒導致逾時 →
    // 限制最多掃 1200 個、候選夠 250 個就停，避免整個 navigate 爆逾時上限。
    var all=doc.querySelectorAll('div,span,li,label');
    var scanMax=Math.min(all.length,1200);
    for(var k=0;k<scanMax && cand.length<250;k++){try{var el=all[k];if(getComputedStyle(el).cursor==='pointer'){var tx=(el.innerText||'').trim();if(tx&&tx.length<=40)cand.push(el);}}catch(e){}}
    for(var j=0;j<cand.length;j++){
      var el=cand[j];var r=el.getBoundingClientRect();
      if(r.width<1||r.height<1)continue;
      var st=getComputedStyle(el);if(st.visibility==='hidden'||st.display==='none'||st.opacity==='0')continue;
      var cx=r.left+r.width/2,cy=r.top+r.height/2;
      if(cx<0||cy<0||cx>innerWidth||cy>innerHeight)continue;
      var role=el.getAttribute('role')||el.tagName.toLowerCase();
      var name=(el.getAttribute('aria-label')||el.getAttribute('placeholder')||el.value||el.innerText||el.getAttribute('title')||'').trim().replace(/\s+/g,' ').slice(0,80);
      if(!name&&el.tagName==='INPUT')name='('+(el.type||'text')+')';
      var key=role+'|'+name+'|'+Math.round(r.top);if(seen[key])continue;seen[key]=1;
      i++;el.setAttribute('data-gwref','e'+i);
      out.push({ref:'e'+i,role:role,name:name,type:el.type||'',cx:Math.round(cx),cy:Math.round(cy)});
      if(out.length>=120)break;
    }
  }
  collect(document);
  var ifr=document.querySelectorAll('iframe');
  for(var f=0;f<ifr.length;f++){try{if(ifr[f].contentDocument)collect(ifr[f].contentDocument);}catch(e){}}
  return JSON.stringify({url:location.href,title:document.title,elements:out});
  }catch(e){return JSON.stringify({url:location.href,title:document.title,elements:[],err:String(e)});}
})()
"""

// click/type 安裝到 window，snapshot 後可用 ref/文字/座標定位並派發真實事件序列
private const val JS_HELPERS = """
(function(){
  function norm(s){return (s||'').replace(/[\s,，、.。()（）\-\/]+/g,'');}
  function find(target){
    var t=(target||'').trim();
    var byref=document.querySelector('[data-gwref="'+t+'"]');if(byref)return byref;
    // 跨 iframe 找 ref
    var ifr=document.querySelectorAll('iframe');
    for(var f=0;f<ifr.length;f++){try{var d=ifr[f].contentDocument;if(d){var r=d.querySelector('[data-gwref="'+t+'"]');if(r)return r;}}catch(e){}}
    // 文字模糊比對（含機場代碼）
    var nt=norm(t),code=(t.match(/\b[A-Z]{3}\b/)||[])[0],best=null;
    var nodes=[].slice.call(document.querySelectorAll('[data-gwref]'));
    for(var f2=0;f2<ifr.length;f2++){try{var d2=ifr[f2].contentDocument;if(d2)nodes=nodes.concat([].slice.call(d2.querySelectorAll('[data-gwref]')));}catch(e){}}
    for(var i=0;i<nodes.length;i++){var el=nodes[i];var nm=(el.getAttribute('aria-label')||el.getAttribute('placeholder')||el.value||el.innerText||'').trim();var nn=norm(nm);
      if(nn===nt)return el;
      if(code&&nm.indexOf(code)>=0)return el;
      if(nt&&(nn.indexOf(nt)>=0||nt.indexOf(nn)>=0))best=best||el;}
    return best;
  }
  function fire(el,type){var ev=new MouseEvent(type,{bubbles:true,cancelable:true,view:window});el.dispatchEvent(ev);}
  window.__gwClick=function(target){
    var el=find(target);if(!el)return 'not-found';
    try{el.scrollIntoView({block:'center'});}catch(e){}
    try{fire(el,'pointerdown');fire(el,'mousedown');fire(el,'pointerup');fire(el,'mouseup');fire(el,'click');}catch(e){return 'err:'+e;}
    return 'OK';
  };
  window.__gwType=function(target,text,submit){
    var el=find(target);if(!el)return 'not-found';
    try{el.scrollIntoView({block:'center'});el.focus();
      var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
      var setter=Object.getOwnPropertyDescriptor(proto,'value');
      if(setter&&setter.set)setter.set.call(el,text);else el.value=text;
      el.dispatchEvent(new Event('input',{bubbles:true}));
      el.dispatchEvent(new Event('change',{bubbles:true}));
      if(submit){var f=el.form;el.dispatchEvent(new KeyboardEvent('keydown',{bubbles:true,key:'Enter',keyCode:13,which:13}));el.dispatchEvent(new KeyboardEvent('keyup',{bubbles:true,key:'Enter',keyCode:13,which:13}));if(f&&f.requestSubmit)try{f.requestSubmit();}catch(e){}}
    }catch(e){return 'err:'+e;}
    return 'OK';
  };
})()
"""

object BrowserJs { const val HELPERS = JS_HELPERS }
