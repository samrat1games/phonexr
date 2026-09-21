#include <QApplication>
#include <QBuffer>
#include <QGuiApplication>
#include <QHostInfo>
#include <QDataStream>
#include <QLabel>
#include <QIcon>
#include <QPushButton>
#include <QProcess>
#include <QStandardPaths>
#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QScreen>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>
#include <QUdpSocket>
#include <QVBoxLayout>

class Window final : public QWidget {
public:
    Window() {
        #ifdef PHONEXR_LITE
        const QString product = "PhoneXR Lite Share";
        #else
        const QString product = "PhoneXR Share";
        #endif
        setWindowTitle(product); setWindowIcon(QIcon(":/PhoneXRShare.png")); resize(520, 300);
        auto *layout = new QVBoxLayout(this);
        auto *title = new QLabel(product); title->setStyleSheet("font-size:28px;font-weight:600");
        status = new QLabel("Остановлено"); status->setWordWrap(true);
        auto *stream = new QPushButton("Передавать экран"); stream->setCheckable(true);
        auto *steam = new QPushButton("Открыть SteamVR");
        layout->addWidget(title); layout->addWidget(new QLabel("Экран → пространственное окно PhoneXR"));
        layout->addSpacing(18); layout->addWidget(stream); layout->addWidget(steam); layout->addWidget(status); layout->addStretch();
        connect(stream, &QPushButton::toggled, this, [=](bool on) { on ? start() : stop(); stream->setText(on ? "Остановить передачу" : "Передавать экран"); });
        connect(steam, &QPushButton::clicked, this, [this] {
            const QString vrpathreg = QStandardPaths::findExecutable("vrpathreg");
            const QString beside = QDir(QCoreApplication::applicationDirPath()).absoluteFilePath("../share/phonexr-share/steamvr/phonexr");
            const QString development = QDir(QCoreApplication::applicationDirPath()).absoluteFilePath("phonexr");
            const QString driver = QFileInfo::exists(beside + "/driver.vrdrivermanifest") ? beside : development;
            if (vrpathreg.isEmpty()) { status->setText("Сначала установите SteamVR в Steam."); return; }
            if (!QFileInfo::exists(driver + "/driver.vrdrivermanifest")) { status->setText("Драйвер PhoneXR не найден — переустановите PhoneXR Share."); return; }
            QProcess registration; registration.start(vrpathreg, {"adddriver", driver}); registration.waitForFinished(5000);
            QProcess::startDetached("steam", {"steam://rungameid/250820"});
            status->setText("Драйвер PhoneXR зарегистрирован · запускаю SteamVR…");
        });
        connect(&frames, &QTimer::timeout, this, [this] { sendFrame(); });
        connect(&announce, &QTimer::timeout, this, [this] {
            QByteArray message = "PHONEXR_DESKTOP_V1 24820 " + QHostInfo::localHostName().toUtf8();
            udp.writeDatagram(message, QHostAddress::Broadcast, 24819);
        });
        connect(&server, &QTcpServer::newConnection, this, [this] { clients << server.nextPendingConnection(); status->setText("PhoneXR подключён · передача 30 FPS"); });
    }
private:
    QLabel *status{}; QTcpServer server; QUdpSocket udp; QTimer frames, announce; QList<QTcpSocket*> clients;
    void start() {
        if (!server.listen(QHostAddress::Any, 24820)) { status->setText(server.errorString()); return; }
        #ifdef PHONEXR_LITE
        frames.start(100);
        #else
        frames.start(50);
        #endif
        announce.start(1000); status->setText("Ожидание PhoneXR в локальной сети…");
    }
    void stop() { frames.stop(); announce.stop(); server.close(); qDeleteAll(clients); clients.clear(); status->setText("Остановлено"); }
    void sendFrame() {
        auto *screen = QGuiApplication::primaryScreen(); if (!screen) return;
        QPixmap shot = screen->grabWindow(0);
        #ifdef PHONEXR_LITE
        if (shot.width() > 1280) shot = shot.scaledToWidth(1280, Qt::SmoothTransformation);
        const int quality = 55;
        #else
        const int quality = 78;
        #endif
        QByteArray jpeg; QBuffer buffer(&jpeg); buffer.open(QIODevice::WriteOnly); shot.save(&buffer, "JPEG", quality);
        QByteArray packet("PXS1", 4); QDataStream out(&packet, QIODevice::Append); out.setByteOrder(QDataStream::BigEndian);
        out << quint16(shot.width()) << quint16(shot.height()) << quint32(jpeg.size()); packet += jpeg;
        for (auto *client : clients) if (client->state() == QAbstractSocket::ConnectedState && client->bytesToWrite() < 2 * packet.size()) client->write(packet);
        clients.removeIf([](QTcpSocket *s) { if (s->state() == QAbstractSocket::ConnectedState) return false; s->deleteLater(); return true; });
    }
};

int main(int argc, char **argv) { QApplication app(argc, argv); Window window; window.show(); return app.exec(); }
