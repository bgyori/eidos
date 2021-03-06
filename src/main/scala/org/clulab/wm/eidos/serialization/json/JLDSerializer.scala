package org.clulab.wm.eidos.serialization.json

import java.util.IdentityHashMap  // Unfortunately borrowed from Java
import java.util.{Set => JavaSet} // Holds keys of IdentityHashMap

import scala.collection.mutable

import org.clulab.odin.Attachment
import org.clulab.odin.Mention
import org.clulab.processors.Document
import org.clulab.processors.Sentence
import org.clulab.struct.DirectedGraph
import org.clulab.struct.Interval
import org.clulab.wm.eidos.Aliases.Quantifier
import org.clulab.wm.eidos.AnnotatedDocument
import org.clulab.wm.eidos.EidosSystem.Corpus
import org.clulab.wm.eidos.groundings.{AdjectiveGrounder, AdjectiveGrounding}
import org.clulab.wm.eidos.attachments._
import org.clulab.wm.eidos.mentions.EidosMention
import org.clulab.wm.eidos.mentions.EidosEventMention
import org.clulab.wm.eidos.mentions.EidosRelationMention
import org.clulab.wm.eidos.mentions.EidosTextBoundMention

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

// This is an object than when asked to convert itself a JSON object or value, converts
// itself in a way that conforms to the JSON-LD standard as well.
abstract class JLDObject(val serializer: JLDSerializer, val typename: String, val value: Any = new Object()) {
  serializer.register(this)
  
  def serialize() = serializer.serialize(this)
  
  def toJsonStr(): String =
      pretty(render(serialize()))

  def toJObject(): JObject
  
  def noneIfEmpty(values: Seq[JValue]) =
      if (values.isEmpty) None
      else Some(values)
  
  def toJObjects(jldObjects: Seq[JLDObject]): Option[Seq[JValue]] =
      noneIfEmpty(jldObjects.map(_.toJObject()).toList)
  
  def newJLDExtraction(mention: EidosMention): JLDExtraction = mention match {
    case mention: EidosEventMention => new JLDDirectedRelation(serializer, mention)
    case mention: EidosRelationMention => new JLDUndirectedRelation(serializer, mention)
    case mention: EidosTextBoundMention => new JLDEntity(serializer, mention)
  }
        
  def isExtractable(mention: EidosMention) = true
  
  def newJLDAttachment(attachment: Attachment, mention: EidosMention): JLDAttachment =
      EidosAttachment.asEidosAttachment(attachment).newJLDAttachment(serializer, mention)
}

object JLDObject {
  // TODO: These terms need to be looked up somewhere.
  val cause = "cause"
  val effect = "effect"
}

// This class helps serialize/convert a JLDObject to JLD by keeping track of
// what types are included and providing IDs so that references to can be made
// within the JSON structure.
class JLDSerializer(val adjectiveGrounder: Some[AdjectiveGrounder]) {
  protected val typenames = mutable.HashSet[String]()
  protected val typenamesByIdentity = new IdentityHashMap[Any, String]()
  protected val idsByTypenameByIdentity: mutable.HashMap[String, IdentityHashMap[Any, Int]] = mutable.HashMap()
  protected val jldObjectsByTypenameByIdentity: mutable.HashMap[String, IdentityHashMap[JLDObject, Int]] = mutable.HashMap()
  
  def register(jldObject: JLDObject) = {
    val identity = jldObject.value
    val typename = jldObject.typename
    
    typenamesByIdentity.put(identity, typename) // So that know which idsByTypenamesByIdentity to look in
    
    val idsByIdentity = idsByTypenameByIdentity.getOrElseUpdate(typename, new IdentityHashMap[Any, Int]())

    if (!idsByIdentity.containsKey(identity))
      idsByIdentity.put(identity, idsByIdentity.size() + 1)

    val jldObjectsByIdentity = jldObjectsByTypenameByIdentity.getOrElseUpdate(typename, new IdentityHashMap[JLDObject,Int]())
    
    jldObjectsByIdentity.put(jldObject, 0)    
  }

  def byTypename(typename: String): JavaSet[JLDObject] = jldObjectsByTypenameByIdentity(typename).keySet()
      
  protected def mkId(typename: String, id: Int): (String, String) =
      ("@id" -> ("_:" + typename + "_" + id))
    
  def mkId(jldObject: JLDObject): (String, String) = {
    val identity = jldObject.value
    val typename = jldObject.typename
    
    typenamesByIdentity.put(identity, typename) // So that know which idsByTypenamesByIdentity to look in
    
    val idsByIdentity = idsByTypenameByIdentity.getOrElseUpdate(typename, new IdentityHashMap[Any, Int]())
    val id = idsByIdentity.get(identity)
    
    mkId(typename, id)
  }
  
  protected def mkType(typename: String): (String, String) = {
    typenames += typename
    ("@type" -> typename)
  }
  
  def mkType(jldObject: JLDObject): (String, String) = mkType(jldObject.typename)

  def mkContext(): JObject = {
    def mkContext(name: String): JField = new JField(name, "#" + name)
    
    val base = new JField("@base", JLDSerializer.base)
    val types = typenames.toList.sorted.map(mkContext)
    
    new JObject(base +: types)
  }
  
  def mkRef(identity: Any): JObject = {
    val typename = typenamesByIdentity.get(identity)
    if (typename == null)
      //return mkId("UnknownType", 0)
      throw new Exception("Cannot make reference to unknown identity: " + identity)
    
    val id = idsByTypenameByIdentity(typename).get(identity)
    
    mkId(typename, id)
  }
    
  def serialize(jldObjectProvider: JLDObject): JValue = {
    // This must be done first in order to collect the context entries
    val jObject = jldObjectProvider.toJObject()
    
    ("@context" -> mkContext) ~
        jObject
  }
  
  def ground(mention: EidosMention, quantifier: Quantifier) =
    if (adjectiveGrounder.isDefined) adjectiveGrounder.get.groundAdjective(mention.odinMention, quantifier)
    else AdjectiveGrounding.noAdjectiveGrounding
}

object JLDSerializer {
  val base = "https://github.com/clulab/eidos/wiki/JSON-LD"
}

class JLDArgument(serializer: JLDSerializer, mention: EidosMention)
    extends JLDObject(serializer, "Argument", mention) {
  
  override def toJObject(): JObject =
      serializer.mkRef(mention)
}

object JLDArgument {
  val singular = "argument"
  val plural = "arguments"
}

class JLDOntologyGrounding(serializer: JLDSerializer, name: String, value: Double)
    extends JLDObject(serializer, "Grounding") {
  
  override def toJObject(): JObject =
      serializer.mkType(this) ~
          ("ontologyConcept" -> name) ~
          ("value" -> value)
}

object JLDOntologyGrounding {
  val singular = "grounding"
  val plural = singular // Mass noun
}

class JLDModifier(serializer: JLDSerializer, quantifier: Quantifier, mention: EidosMention)
    extends JLDObject(serializer, "Modifier") {

  override def toJObject(): JObject = {
    val grounding = serializer.ground(mention, quantifier)
    
    serializer.mkType(this) ~
        ("text" -> quantifier) ~
        // This is not the mention you are looking for.
        // See also skipPositions.
        //(JLDProvenance.singular -> new JLDProvenance(serializer, mention).toJObject()) ~
        ("intercept" -> grounding.intercept) ~
        ("mu" -> grounding.mu) ~
        ("sigma" -> grounding.sigma)
  }
}

object JLDModifier {
  val singular = "modifier"
  val plural = "modifiers"
}

class JLDAttachment(serializer: JLDSerializer, kind: String, text: String, modifiers: Option[Seq[Quantifier]], mention: EidosMention)
    extends JLDObject(serializer, "State") {
  
  override def toJObject(): JObject = {
    val jldModifiers =
        if (!modifiers.isDefined) Nil
        else modifiers.get.map(new JLDModifier(serializer, _, mention).toJObject()).toList
    
    serializer.mkType(this) ~
        ("type", kind) ~
        ("text", text) ~
        // This is also not the mention you are looking for
        //(JLDProvenance.singular -> new JLDProvenance(serializer, mention).toJObject()) ~
        (JLDModifier.plural -> noneIfEmpty(jldModifiers))
  }
}

object JLDAttachment {
  val singular = "state"
  val plural = "states"
}

class JLDInterval(serializer: JLDSerializer, interval: Interval)
    extends JLDObject(serializer, "Interval") {

  override def toJObject(): JObject =
      serializer.mkType(this) ~
          ("start", interval.start + 1) ~ // Start at 1.
          ("end", interval.end) // It is now inclusive.
}

object JLDInterval {
  val singular = "position"
  val plural = "positions"
}

class JLDProvenance(serializer: JLDSerializer, mention: EidosMention)
    // Do not include the mention here because provenances are not to be referenced!
    extends JLDObject(serializer, "Provenance") {
  
  override def toJObject(): JObject = {
    val skipPositions = false
    val document = mention.odinMention.document
    val sentence = mention.odinMention.sentenceObj

    if (skipPositions) // For the states when we don't have them
      serializer.mkType(this) ~
        (JLDDocument.singular -> serializer.mkRef(document)) ~
        (JLDSentence.singular -> serializer.mkRef(sentence))
    else {
      // Try to find the matching ones by document, sentence, and position with eq
//      val allJldWords = serializer.byTypename(JLDWord.typename).asScala.map(_.asInstanceOf[JLDWord])
//      val filteredJldWords = mention.start.until(mention.end).map { i => // This is done to keep the words in order
//        allJldWords.find(jldWord => jldWord.document.eq(document) && jldWord.sentence.eq(sentence) && i == jldWord.index)
//      }.filter(_.isDefined).map(_.get)      
//      val refJldWords = filteredJldWords.map(jldWord => serializer.mkRef(jldWord.value))
            
      serializer.mkType(this) ~
          (JLDDocument.singular -> serializer.mkRef(document)) ~
          (JLDSentence.singular -> serializer.mkRef(sentence)) ~ // TODO: use tokenIntervals
          (JLDInterval.plural -> new JLDInterval(serializer, mention.odinMention.tokenInterval).toJObject)
//          ("references" -> refJldWords)
    }
  }
}

object JLDProvenance {
  val singular = "provenance"
  val plural = "provenances"
}

class JLDTrigger(serializer: JLDSerializer, mention: EidosMention)
    extends JLDObject(serializer, "Trigger", mention) {
  
  override def toJObject(): JObject =
      serializer.mkType(this) ~
          ("text" -> mention.odinMention.text) ~
          (JLDProvenance.singular -> toJObjects(Seq(new JLDProvenance(serializer, mention))))
}

object JLDTrigger {
  val singular = "trigger"
  val plural = "triggers"
}

abstract class JLDExtraction(serializer: JLDSerializer, typename: String, mention: EidosMention) extends JLDObject(serializer, typename, mention) {
 
  def getMentions(): Seq[EidosMention] = Nil
  
  override def toJObject(): JObject = {
    val jldAttachments = mention.odinMention.attachments.map(newJLDAttachment(_, mention)).toList
    val ontologyGrounding = mention.grounding.grounding
    //val ontologyGrounding = new OntologyGrounding(Seq(("hello", 4.5d), ("bye", 1.0d))).grounding
    val jldGroundings = toJObjects(ontologyGrounding.map(pair => new JLDOntologyGrounding(serializer, pair._1, pair._2)))
    
    serializer.mkType(this) ~
        serializer.mkId(this) ~
        ("labels" -> mention.odinMention.labels) ~
        ("text" -> mention.odinMention.text) ~
        ("rule" -> mention.odinMention.foundBy) ~
        ("canonicalName" -> mention.canonicalName) ~
        ("grounding" -> jldGroundings) ~
        ("score" -> None) ~ // Figure out how to look up?, maybe like the sigma
        (JLDProvenance.singular -> toJObjects(Seq(new JLDProvenance(serializer, mention)))) ~
        (JLDAttachment.plural -> toJObjects(jldAttachments))
  }
}

object JLDExtraction {
  val singular = "extraction"
  val plural = "extractions"
}

class JLDEntity(serializer: JLDSerializer, mention: EidosMention) 
    extends JLDExtraction(serializer, JLDEntity.typename, mention) {
  
  override def toJObject(): JObject =
     super.toJObject()
}

object JLDEntity {
  val typename = "Entity"
}

class JLDDirectedRelation(serializer: JLDSerializer, mention: EidosEventMention)
    extends JLDExtraction(serializer, JLDDirectedRelation.typename, mention) {

  override def getMentions(): Seq[EidosMention] = {
    val sources = mention.eidosArguments.getOrElse(JLDObject.cause, Nil).filter(isExtractable)
    val targets = mention.eidosArguments.getOrElse(JLDObject.effect, Nil).filter(isExtractable)
    
    // Know which kind of mention
    //val trigger = mention.asInstanceOf[EidosEventMention].odinTrigger
    
    sources ++ targets // :+ trigger
  }
  
  override def toJObject(): JObject = {
    val trigger = new JLDTrigger(serializer, mention.eidosTrigger).toJObject()
    val sources = mention.eidosArguments.getOrElse(JLDObject.cause, Nil).filter(isExtractable)
    val targets = mention.eidosArguments.getOrElse(JLDObject.effect, Nil).filter(isExtractable)
    
    super.toJObject() ~
        (JLDTrigger.singular -> trigger) ~
        ("sources" -> sources.map(serializer.mkRef)) ~
        ("destinations" -> targets.map(serializer.mkRef))
  }
}

object JLDDirectedRelation {
  val typename = "DirectedRelation"
}

class JLDUndirectedRelation(serializer: JLDSerializer, mention: EidosRelationMention)
    extends JLDExtraction(serializer, JLDUndirectedRelation.typename, mention) {
  
  override def getMentions(): Seq[EidosMention] =
    mention.eidosArguments.values.flatten.toSeq.filter(isExtractable)

  override def toJObject(): JObject = {
    val arguments = mention.eidosArguments.values.flatten.filter(isExtractable) // The keys are skipped
    val argumentMentions =
        if (arguments.isEmpty) Nil
        else arguments.map(serializer.mkRef(_)).toList

    super.toJObject() ~
        (JLDArgument.plural -> noneIfEmpty(argumentMentions))
  }
}

object JLDUndirectedRelation {
  val typename = "UndirectedRelation"
}

class JLDDependency(serializer: JLDSerializer, edge: (Int, Int, String), words: Seq[JLDWord])
    extends JLDObject(serializer, "Dependency") {

  override def toJObject(): JObject = {
    val source = words(edge._1).value
    val destination = words(edge._2).value
    val relation = edge._3
    
    serializer.mkType(this) ~
        ("source" -> serializer.mkRef(source)) ~
        ("destination" -> serializer.mkRef(destination)) ~
        ("relation" -> relation)
  }  
}

object JLDDependency {
  val singular = "dependency"
  val plural = "dependencies"
}

class JLDGraphMapPair(serializer: JLDSerializer, key: String, directedGraph: DirectedGraph[String], words: Seq[JLDWord])
    extends JLDObject(serializer, "Dependencies") {
 
  def toJObject(): JObject = JObject()
  
  def toJValue(): JValue = {
    val jldEdges = directedGraph.allEdges.map(new JLDDependency(serializer, _, words).toJObject())
    
    new JArray(jldEdges)
  }
}

class JLDWord(serializer: JLDSerializer, val document: Document, val sentence: Sentence, val index: Int)
    // The document, sentence, index above will be used to recognized words.
    extends JLDObject(serializer, JLDWord.typename) {
  
  override def toJObject(): JObject = {
    def getOrNone(optionArray: Option[Array[String]]): Option[String] =
        if (!optionArray.isDefined) None
        else Option(optionArray.get(index))
     
    val startOffset = sentence.startOffsets(index)
    val endOffset = sentence.endOffsets(index)
    val jldText =
        if (!document.text.isDefined) None
        else Option(document.text.get.substring(startOffset, endOffset))

    serializer.mkType(this) ~
        serializer.mkId(this) ~
        ("text" -> jldText) ~
        ("tag" -> getOrNone(sentence.tags)) ~
        ("entity" -> getOrNone(sentence.entities)) ~
        ("startOffset" -> startOffset) ~
        ("endOffset" -> endOffset) ~
        ("lemma" -> getOrNone(sentence.lemmas)) ~
        ("chunk" -> getOrNone(sentence.chunks))
  }
}

object JLDWord {
  val singular = "word"
  val plural = "words"
  val typename = "Word"
}

class JLDSentence(serializer: JLDSerializer, document: Document, sentence: Sentence)
    extends JLDObject(serializer, "Sentence", sentence) {
  
  override def toJObject(): JObject = {
    val key = "universal-enhanced"
    val jldWords = sentence.words.indices.map(new JLDWord(serializer, document, sentence, _))
    val dependencies = sentence.graphs.get(key)
    // This is given access to the words because they are nicely in order and no searching need be done.
    val jldGraphMapPair =
        if (!dependencies.isDefined) None
        else Some(new JLDGraphMapPair(serializer, key, dependencies.get, jldWords).toJValue())
          
    serializer.mkType(this) ~
        serializer.mkId(this) ~
        ("text" -> sentence.getSentenceText()) ~
        (JLDWord.plural -> toJObjects(jldWords)) ~
        (JLDDependency.plural -> jldGraphMapPair)
  }
}

object JLDSentence {
  val singular = "sentence"
  val plural = "sentences"
}

class JLDDocument(serializer: JLDSerializer, annotatedDocument: AnnotatedDocument)
    extends JLDObject(serializer, "Document", annotatedDocument.document) {
  
  override def toJObject(): JObject = {
    val jldSentences = annotatedDocument.document.sentences.map(new JLDSentence(serializer, annotatedDocument.document, _))
      
      serializer.mkType(this) ~
          serializer.mkId(this) ~
          ("title" -> annotatedDocument.document.id) ~
          (JLDSentence.plural -> toJObjects(jldSentences))
  }
}

object JLDDocument {
  val singular = "document"
  val plural = "documents"
}

class JLDCorpus(serializer: JLDSerializer, corpus: Corpus)
    extends JLDObject(serializer, "Corpus", corpus) {
  
  def this(corpus: Corpus, entityGrounder: AdjectiveGrounder) = this(new JLDSerializer(Some(entityGrounder)), corpus)
  
  protected def collectMentions(mentions: Seq[EidosMention], mapOfMentions: IdentityHashMap[EidosMention, Int]): Seq[JLDExtraction] = {
    val newMentions = mentions.filter(isExtractable(_)).filter { mention => 
      if (mapOfMentions.containsKey(mention))
        false
      else {
        mapOfMentions.put(mention, mapOfMentions.size() + 1)
        true
      }
    }
    
    if (!newMentions.isEmpty) {
      val jldExtractions = newMentions.map(newJLDExtraction(_))
      val recMentions = jldExtractions.flatMap(_.getMentions())
      
      jldExtractions ++ collectMentions(recMentions, mapOfMentions)
    }
    else
      Nil
  }
  
  protected def collectMentions(mentions: Seq[EidosMention]): Seq[JLDExtraction] = {
    val ordering = Array(JLDEntity.typename, JLDDirectedRelation.typename, JLDUndirectedRelation.typename)
    val mapOfMentions = new IdentityHashMap[EidosMention, Int]()

    def lt(left: JLDExtraction, right: JLDExtraction) = {
      val leftOrdering = ordering.indexOf(left.typename)
      val rightOrdering = ordering.indexOf(right.typename)
      
      if (leftOrdering != rightOrdering)
        leftOrdering < rightOrdering
      else
        mapOfMentions.get(left.value) < mapOfMentions.get(right.value)
    }

    collectMentions(mentions, mapOfMentions).sortWith(lt)
  }
  
  override def toJObject(): JObject = {
    val jldDocuments = corpus.map(new JLDDocument(serializer, _))
    val eidosMentions = corpus.flatMap(_.eidosMentions)
    val jldExtractions = collectMentions(eidosMentions)
    
//    val index1 = 0.until(mentions.size).find(i => mentions(i).matches("DirectedRelation"))
//    if (index1.isDefined) {
//      val position1 = mentions(index1.get).end
//      println("position1 " + position1)
//     
//      val index2 = index1.get + 1
//      if (index2 < mentions.size) {
//        val position2 = mentions(index2).end
//        println("position2 " + position2)
//      }
//    }

    serializer.mkType(this) ~
        (JLDDocument.plural -> toJObjects(jldDocuments)) ~
        (JLDExtraction.plural -> toJObjects(jldExtractions))
  }
}

object JLDCorpus {
  val singular = "corpus"
  val plural = "corpora"
}
