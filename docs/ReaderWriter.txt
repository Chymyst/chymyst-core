import concurrency;

class ReaderWriter with {
  
  module join with {
    def startRead: Unit;
    def startWrite: Unit;
    def endRead: nil;
    def endWrite: nil;
    
    private def startRead1: Unit;
    private def startWrite1: Unit;
    private def readers(n: Int): nil;
    private def noReaders: nil;
    private def writers(n: Int): nil;
    private def noWriters: nil;
    
    startRead & noWriters = reply (startRead1) to startRead;
    startRead1 & noReaders = spawn < (reply to startRead1) | noWriters | readers(1) >;
    startRead1 & readers(n) = spawn < (reply to startRead1) | noWriters | readers(n+1) >;      
    
    startWrite & noWriters = spawn < (reply (startWrite1) to startWrite) | writers(1) >;
    startWrite & writers(n) = spawn < (reply (startWrite1) to startWrite) | writers(n+ 1) >;
    startWrite1 & noReaders = reply to startWrite1;
    
    endRead & readers(n) = if (n == 1) noReaders else readers(n-1);
    endWrite & writers(n) = spawn < noReaders | (if (n == 1) noWriters else writers(n-1)) >; 
  }

  def read[a](def p: a): a = { startRead; val x = p; endRead; x }
  
  def write[a](def p: a): a = { startWrite; val x = p; endWrite; x }
  
  def run: Unit = spawn < noReaders | noWriters >;

}

module readerWriterTest with {
  
  val random = new java.util.Random(); 
  
  def reader(i: Int, m: ReaderWriter): Unit = {
    sleep(1 + random.nextInt(1000));
    System.err.println("Reader " + i + " wants to read.");
    m read { System.err.println("Reader " + i + " is reading.") };
    System.err.println("Reader " + i + " has read.");
    reader(i, m)
  }
  
  def writer(i: Int, m: ReaderWriter): Unit = {
    sleep(1 + random.nextInt(1000));
    System.err.println("Writer " + i + " wants to write.");
    m write { System.err.println("Writer " + i + " is writing.") };
    System.err.println("Writer " + i + " has written.");
    writer(i, m)
  }

  def main(args: Array[String]): Unit = {
    val m = new ReaderWriter;
    range(1, 5) foreach (i => spawn < reader(i, m) >);
    range(1, 5) foreach (i => spawn < writer(i, m) >);
    m.run
  }

}
