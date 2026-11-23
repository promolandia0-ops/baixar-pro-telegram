package br.geracaoai.baixartg.core

/**
 * Detecção e confirmação automática genérica de verificação de idade (age gate).
 * Nunca clica em botões de sair/recusar ("sair", "leave", "no", "não", "menor").
 */
object AgeGate {
    private val NEGATIVE = Regex(
        """(?i)\b(?:sair|leave|exit|no|n[aã]o|nao|under\s*18|menor(?:\s*de\s*idade)?|decline|recusar|cancel|cancelar|back|voltar|disagree|discordo|close)\b"""
    )
    private val STRONG_POSITIVE = Regex(
        """(?i)(?:tenho\s*(?:mais\s*de\s*)?18|sou\s*maior|maior\s*de\s*(?:18|idade)|i\s*am\s*(?:over\s*)?18|i'?m\s*(?:over\s*)?18|over\s*18|\b18\s*\+|sim,?\s*(?:tenho|sou)\s*(?:maior|18))"""
    )
    private val CONTEXT_POSITIVE = Regex(
        """(?i)^(?:enter(?:\s*site)?|entrar|continuar|continue|yes|sim|agree|concordo|accept(?:\s*all)?|aceitar|confirm|confirmar|proceed|prosseguir|ok)$"""
    )
    private val AGE_CONTEXT = Regex(
        """(?i)(?:\bage\b|idade|adult|18\+|over\s*18|maior\s*de\s*18|verifica[cç][aã]o\s*de\s*idade|age[-_\s]?(?:gate|verif|check|modal|overlay|wall))"""
    )

    fun isConfirmLabel(raw: String, inAgeContext: Boolean): Boolean {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 60) return false
        if (NEGATIVE.containsMatchIn(t)) return false
        if (STRONG_POSITIVE.containsMatchIn(t)) return true
        return inAgeContext && CONTEXT_POSITIVE.matches(t)
    }

    fun isAgeContextText(text: String): Boolean = AGE_CONTEXT.containsMatchIn(text)

    const val BYPASS_JS = """(function(){
try{
  var neg=/\b(?:sair|leave|exit|no|n[aã]o|nao|under\s*18|menor(?:\s*de\s*idade)?|decline|recusar|cancel|cancelar|back|voltar|disagree|discordo|close)\b/i;
  var strong=/(?:tenho\s*(?:mais\s*de\s*)?18|sou\s*maior|maior\s*de\s*(?:18|idade)|i\s*am\s*(?:over\s*)?18|i'?m\s*(?:over\s*)?18|over\s*18|\b18\s*\+|sim,?\s*(?:tenho|sou)\s*(?:maior|18))/i;
  var ctxPos=/^(?:enter(?:\s*site)?|entrar|continuar|continue|yes|sim|agree|concordo|accept(?:\s*all)?|aceitar|confirm|confirmar|proceed|prosseguir|ok)$/i;
  var ageHint=/(?:\bage\b|idade|adult|18\+|over\s*18|maior\s*de\s*18|verifica[cç][aã]o\s*de\s*idade|age[-_\s]?(?:gate|verif|check|modal|overlay|wall)|are\s+you\s+18|voc[eê]\s+tem\s+18)/i;
  function vis(el){
    if(!el||el.disabled)return false;
    var st=window.getComputedStyle?getComputedStyle(el):null;
    if(st&&(st.display==='none'||st.visibility==='hidden'||parseFloat(st.opacity)===0))return false;
    var r=el.getBoundingClientRect?el.getBoundingClientRect():null;
    return !!r&&r.width>4&&r.height>4;
  }
  function hasAgeModal(el){
    var p=el,k=0;
    while(p&&k<8){
      var s=((p.id||'')+' '+(p.className||'')+' '+(p.getAttribute&&p.getAttribute('aria-label')||'')+' '+((p.getAttribute&&p.getAttribute('role'))||'')).toLowerCase();
      if(/age|idade|adult|18|maior|verif/.test(s))return true;
      var role=p.getAttribute&&p.getAttribute('role');
      if((role==='dialog'||p.getAttribute&&p.getAttribute('aria-modal')==='true'||/modal|overlay|gate|wall|popup/.test(s))&&ageHint.test((p.innerText||p.textContent||'').slice(0,800)))return true;
      p=p.parentElement;k++;
    }
    return false;
  }
  var pageTxt=(document.body?(document.body.innerText||document.body.textContent||''):'').slice(0,2500);
  var pageHasAge=ageHint.test(pageTxt)||!!document.querySelector('[id*="age" i][class*="modal" i],[class*="age-gate" i],[class*="agegate" i],[id*="age-gate" i],[id*="agegate" i],[class*="idade" i]');
  var btns=document.querySelectorAll('button,[role=button],a,input[type=button],input[type=submit]');
  var clicked=0, clickedLbl='', detected=pageHasAge?1:0;
  for(var i=0;i<btns.length;i++){
    var el=btns[i];
    if(!vis(el))continue;
    var lbl=((el.innerText||el.textContent||el.value||el.getAttribute('aria-label')||el.getAttribute('title')||'')+'').replace(/\s+/g,' ').trim();
    if(!lbl||lbl.length>60||neg.test(lbl))continue;
    var inCtx=hasAgeModal(el);
    if(inCtx)detected=1;
    if(strong.test(lbl)||((inCtx||pageHasAge)&&ctxPos.test(lbl))){
      try{el.scrollIntoView({block:'center',behavior:'instant'})}catch(e){}
      try{el.click()}catch(e){}
      try{el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}))}catch(e){}
      clicked=1; clickedLbl=lbl; break;
    }
  }
  return JSON.stringify({detected:detected,clicked:clicked,label:clickedLbl});
}catch(e){return JSON.stringify({detected:0,clicked:0,label:''})}
})()"""
}
