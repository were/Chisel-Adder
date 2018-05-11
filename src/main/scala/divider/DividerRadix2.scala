package examples

import chisel3._

class Radix2Sel extends Module {
    val io = IO(new Bundle {
        val ys = Input(UInt(4.W))
        val yc = Input(UInt(4.W))
        val qs = Output(UInt(1.W))
        val qm = Output(UInt(1.W))
    })
    val p = io.ys ^ io.yc
    val g = io.ys & io.yc
    io.qm := ~(p(0) & p(1) & p(2))
    io.qs := p(3) ^ (g(2) | (p(2) & g(1)) | (p(2) & p(1) & g(0)))
}

class DividerRadix2Updater(val n: Int) extends Module {
    val io = IO(new Bundle {
        val ws = Input(UInt((n + 2).W))
        val wc = Input(UInt((n + 2).W))
        val divisor = Input(UInt(n.W))
        val qs = Output(UInt(1.W))
        val qm = Output(UInt(1.W))
        val ows = Output(UInt((n + 2).W))
        val owc = Output(UInt((n + 2).W))
        // Debug output
        val debug_q = Output(SInt(3.W))
        val debug_csa_a = Output(UInt((n + 2).W))
        val debug_csa_b = Output(UInt((n + 2).W))
        val debug_csa_cin = Output(UInt((n + 2).W))
    })
    
    // Select q.
    val selector = Module(new Radix2Sel())
    selector.io.ys := io.ws(n + 1, n + 1 - 3)
    selector.io.yc := io.wc(n + 1, n + 1 - 3)
    io.qs := selector.io.qs
    io.qm := selector.io.qm

    // Feed to csa.
    val csa = Module(new CarrySaveAdder(n + 2))
    csa.io.A := util.Cat(io.ws(n, 0), 0.U(1.W))
    io.debug_csa_a := util.Cat(io.ws(n, 0), 0.U(1.W))
    // Prepare the input to csa.
    // cin = -q * divisor
    when (selector.io.qs === 0.U) {
        // q === 1.
        // Notice that the last bit of B is 1.
        // This comes from the -d = ~d + 1.
        csa.io.B := util.Cat(io.wc(n, 0), 1.U(1.W))
        csa.io.Cin := (0x300.U | (~io.divisor)) & 0x3FF.U

        io.debug_q := 1.S
        io.debug_csa_b := util.Cat(io.wc(n, 0), 1.U(1.W))
        io.debug_csa_cin := (0x300.U | (~io.divisor)) & 0x3FF.U
    } .elsewhen (selector.io.qm === 0.U) {
        // q === 0.
        csa.io.B := util.Cat(io.wc(n, 0), 0.U(1.W))
        csa.io.Cin := 0.U((n + 2).W)

        io.debug_q := 0.S
        io.debug_csa_b := util.Cat(io.wc(n, 0), 0.U(1.W))
        io.debug_csa_cin := 0.U((n + 2).W)
    } .otherwise {
        // q === -1
        csa.io.B := util.Cat(io.wc(n, 0), 0.U(1.W))
        csa.io.Cin := util.Cat(0.U(2.W), io.divisor)

        io.debug_q := (-1).S
        io.debug_csa_b := util.Cat(io.wc(n, 0), 0.U(1.W))
        io.debug_csa_cin := util.Cat(0.U(2.W), io.divisor)
    }

    io.ows := csa.io.Sum
    io.owc := csa.io.Cout

}

class OnlineConverterControlRadix2() extends Module {
    val io = IO(new Bundle {
        val qm = Input(UInt(1.W))
        val qs = Input(UInt(1.W))
        val qin = Output(UInt(1.W))
        val qmin = Output(UInt(1.W))
        // For load signal, 0 => q, 1 => qm
        val qload = Output(UInt(1.W))
        val qmload = Output(UInt(1.W))
    })

    when (io.qs === 0.U) {
        // q = 1.
        io.qin := 1.U(1.W)
        io.qmin := 0.U(1.W)
        io.qload := 0.U(1.W)
        io.qmload := 0.U(1.W)
    } .elsewhen (io.qm === 0.U) {
        // q = 0.
        io.qin := 0.U(1.W)
        io.qmin := 1.U(1.W)
        io.qload := 0.U(1.W)
        io.qmload := 1.U(1.W)
    } .otherwise {
        // q = -1.
        io.qin := 1.U(1.W)
        io.qmin := 0.U(1.W)
        io.qload := 1.U(1.W)
        io.qmload := 1.U(1.W)
    }
}

class Mux2 extends Module {
  val io = IO(new Bundle {
    val sel = Input(UInt(1.W))
    val in0 = Input(UInt(1.W))
    val in1 = Input(UInt(1.W))
    val out = Output(UInt(1.W))
  })
  io.out := (io.sel & io.in1) | (~io.sel & io.in0)
}

class Multiplexer(val n: Int) extends Module {
    val io = IO(new Bundle {
        val a = Input(UInt(n.W))
        val b = Input(UInt(n.W))
        // sel = 0 -> a, sel = 1 => b
        val sel = Input(UInt(1.W))
        val o = Output(UInt(n.W))
    })

    val Mux2s = Array.fill(n)(Module(new Mux2()))
    val out = Wire(Vec(n, UInt(1.W)))
    for (i <- 0 until n) {
        Mux2s(i).io.in0 := io.a(i)
        Mux2s(i).io.in1 := io.b(i)
        Mux2s(i).io.sel := io.sel
        out(i) := Mux2s(i).io.out
    }

    io.o := out.asUInt
}

class OnlineConverterRadix2(val n: Int) extends Module {
    val io = IO(new Bundle {
        val qm = Input(UInt(1.W))
        val qs = Input(UInt(1.W))
        val load = Input(Bool())
        val quotient = Output(UInt(n.W))
    })

    val Q = Reg(UInt(n.W))
    val QM = Reg(UInt(n.W))

    val control = Module(new OnlineConverterControlRadix2())
    control.io.qm := io.qm
    control.io.qs := io.qs

    val mux_q = Module(new Multiplexer(n))
    val mux_qm = Module(new Multiplexer(n))

    mux_q.io.a := Q
    mux_q.io.b := QM
    mux_q.io.sel := control.io.qload
    mux_qm.io.a := Q
    mux_qm.io.b := QM
    mux_qm.io.sel := control.io.qmload

    when (io.load) {
        Q := 0.U(n.W)
        QM := 0.U(n.W)
    } .otherwise {
        Q := util.Cat(mux_q.io.o(n - 2, 0), control.io.qin)
        QM := util.Cat(mux_qm.io.o(n - 2, 0), control.io.qmin)
    }

    io.quotient := Q
}

// The input should have n decial digits and
// be like 0.1xxxxxx
class DividerRadix2(val n: Int) extends Module {
    val io = IO(new Bundle {
        val dividend = Input(UInt(n.W))
        val divisor = Input(UInt(n.W))
        val load = Input(Bool())
        val quotient = Output(UInt(n.W))
        val remainder = Output(UInt(n.W))
    })
    // two integer bits for partial remainder.
    val ws = Reg(UInt((n + 2).W))
    val wc = Reg(UInt((n + 2).W))
    val partial_quotient = Reg(UInt(n.W))

    val updater = Module(new DividerRadix2Updater(n))
    updater.io.ws := ws
    updater.io.wc := wc
    updater.io.divisor := io.divisor

    val converter = Module(new OnlineConverterRadix2(n))
    converter.io.load := io.load
    converter.io.qs := updater.io.qs
    converter.io.qm := updater.io.qm
    io.quotient := converter.io.quotient

    when (io.load) {
        // Initialize with dividend.
        ws := util.Cat(0.U(3.W), io.dividend(n - 1, 1))
        wc := 0.U
    } .otherwise {
        ws := updater.io.ows
        wc := updater.io.owc
    } 

    io.remainder := ws
}